package com.harness.preprocess.memory;

import com.harness.core.model.Session;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages session lifecycle: creation, timeout detection, and quality filtering.
 * Passive timeout detection runs on every request.
 */
public class SessionLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(SessionLifecycleManager.class);

    private final SessionStore sessionStore;
    private final MessageStore messageStore;
    private final Duration timeout;
    private final int minMessages;
    private final int minUserChars;
    private final int refinementMinScore;

    public SessionLifecycleManager(SessionStore sessionStore, MessageStore messageStore) {
        this.sessionStore = sessionStore;
        this.messageStore = messageStore;
        EnvConfig cfg = EnvConfig.get();
        int timeoutMinutes = cfg.getInt(EnvKey.SESSION_TIMEOUT_MINUTES, 30);
        this.timeout = Duration.ofMinutes(timeoutMinutes);
        this.minMessages = cfg.getInt(EnvKey.MEMORY_MIN_MESSAGES, 5);
        this.minUserChars = cfg.getInt(EnvKey.MEMORY_MIN_USER_CHARS, 100);
        this.refinementMinScore = cfg.getInt(EnvKey.MEMORY_REFINEMENT_MIN_SCORE, 3);
    }

    /**
     * Result of session lifecycle processing.
     */
    public record LifecycleResult(
            Session session,
            boolean isNewSession,
            List<String> timedOutSessionIds
    ) {}

    /**
     * Process session lifecycle for an incoming request.
     * 1. Find and close timed-out sessions for this user
     * 2. Resolve or create the current session
     */
    public LifecycleResult process(String userId, String requestedSessionId) {
        // Close timed-out sessions for this user
        List<String> timedOutIds = closeTimedOutSessions(userId);

        // Resolve current session
        Session session;
        boolean isNew = false;

        if (requestedSessionId != null && !requestedSessionId.isBlank()) {
            // Try to find the requested session
            session = sessionStore.findActive(requestedSessionId).orElse(null);
            if (session == null) {
                // Session not found or already closed, create new
                session = sessionStore.create(userId);
                isNew = true;
                log.debug("Requested session {} not found, created new {}", requestedSessionId, session.id());
            }
        } else {
            // No session requested, create new
            session = sessionStore.create(userId);
            isNew = true;
            log.debug("No session requested, created new {} for user {}", session.id(), userId);
        }

        return new LifecycleResult(session, isNew, timedOutIds);
    }

    /**
     * Close all timed-out sessions for a user and return their IDs.
     * Marks worthy sessions as 'pending' refinement to prevent duplicate processing.
     */
    private List<String> closeTimedOutSessions(String userId) {
        List<Session> activeSessions = sessionStore.findActiveByUser(userId);
        List<String> timedOutIds = new ArrayList<>();
        for (Session s : activeSessions) {
            if (isTimedOut(s)) {
                sessionStore.close(s.id(), Session.SessionStatus.timeout);
                timedOutIds.add(s.id());
                log.info("Closed timed-out session {} for user {} (lastActive={})", s.id(), userId, s.lastActive());

                // Mark as pending if worthy, so the cleanup scheduler can claim it
                if (isWorthyOfRefinement(s.id())) {
                    sessionStore.markRefinementStatus(s.id(), "pending");
                    log.debug("Marked session {} as pending refinement", s.id());
                }
            }
        }
        return timedOutIds;
    }

    private boolean isTimedOut(Session session) {
        return session.lastActive().plus(timeout).isBefore(java.time.Instant.now());
    }

    /**
     * Check if a session meets quality thresholds for preference refinement.
     * Requires BOTH the existing thresholds (message count + char count) AND a minimum refinement score.
     */
    public boolean isWorthyOfRefinement(String sessionId) {
        int msgCount = messageStore.countUserMessages(sessionId);
        int charCount = messageStore.sumUserContentLength(sessionId);

        // Basic threshold check
        if (msgCount < minMessages || charCount < minUserChars) {
            log.debug("Session {} not worthy of refinement: msgs={}/{}, chars={}/{}",
                    sessionId, msgCount, minMessages, charCount, minUserChars);
            return false;
        }

        // Scoring check
        int score = calculateRefinementScore(sessionId);
        if (score < refinementMinScore) {
            log.debug("Session {} below refinement score threshold: score={}/{}",
                    sessionId, score, refinementMinScore);
            return false;
        }

        return true;
    }

    /**
     * Calculate a refinement quality score for a session.
     * Each signal adds 1 point. Higher scores indicate more valuable sessions.
     *
     * Signals:
     * 1. Conversation turns >= 3 (meaningful back-and-forth)
     * 2. Tool usage present (messages with tool results)
     * 3. Average AI reply length >= 200 chars (detailed responses)
     * 4. User messages contain questions or intent keywords
     */
    public int calculateRefinementScore(String sessionId) {
        int score = 0;

        // Signal 1: Conversation turns (user+assistant pairs)
        int turns = messageStore.countConversationTurns(sessionId);
        if (turns >= 3) {
            score++;
        }

        // Signal 2: Tool usage
        int toolMessages = messageStore.countToolMessages(sessionId);
        if (toolMessages > 0) {
            score++;
        }

        // Signal 3: Average AI reply length
        int avgReplyLen = messageStore.avgAssistantReplyLength(sessionId);
        if (avgReplyLen >= 200) {
            score++;
        }

        // Signal 4: User questions or intent
        boolean hasQuestions = messageStore.hasUserQuestions(sessionId);
        if (hasQuestions) {
            score++;
        }

        log.debug("Session {} refinement score: {} (turns={}, tools={}, avgLen={}, hasQuestions={})",
                sessionId, score, turns, toolMessages, avgReplyLen, hasQuestions);
        return score;
    }

    public Duration getTimeout() {
        return timeout;
    }
}

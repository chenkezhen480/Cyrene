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
            // Try to find the requested session (active first)
            session = sessionStore.findActive(requestedSessionId).orElse(null);
            if (session == null) {
                // Not active — check if it exists (closed/timed-out) and reopen
                session = sessionStore.findById(requestedSessionId).orElse(null);
                if (session != null) {
                    sessionStore.updateLastActive(requestedSessionId);
                    log.debug("Reopened closed session {} for user {}", requestedSessionId, userId);
                } else {
                    // Truly doesn't exist, create new
                    session = sessionStore.create(userId);
                    isNew = true;
                    log.debug("Requested session {} not found, created new {}", requestedSessionId, session.id());
                }
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
                log.debug("Closed timed-out session {} for user {} (lastActive={})", s.id(), userId, s.lastActive());

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
     * Uses a single consolidated query for all stats.
     */
    public boolean isWorthyOfRefinement(String sessionId) {
        MessageStore.SessionStats stats = messageStore.loadSessionStats(sessionId);

        if (stats.userMsgCount() < minMessages || stats.userCharCount() < minUserChars) {
            log.debug("Session {} not worthy: msgs={}/{}, chars={}/{}",
                    sessionId, stats.userMsgCount(), minMessages, stats.userCharCount(), minUserChars);
            return false;
        }

        int score = calculateRefinementScoreFromStats(stats);
        if (score < refinementMinScore) {
            log.debug("Session {} below score threshold: {}/{}", sessionId, score, refinementMinScore);
            return false;
        }
        return true;
    }

    /**
     * Calculate refinement score from pre-loaded stats (no additional DB calls).
     */
    private int calculateRefinementScoreFromStats(MessageStore.SessionStats stats) {
        int score = 0;
        if (stats.conversationTurns() >= 3) score++;
        if (stats.toolMsgCount() > 0) score++;
        if (stats.avgAssistantReplyLen() >= 200) score++;
        if (stats.hasUserQuestions()) score++;
        return score;
    }

    public Duration getTimeout() {
        return timeout;
    }
}

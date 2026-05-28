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

    public SessionLifecycleManager(SessionStore sessionStore, MessageStore messageStore) {
        this.sessionStore = sessionStore;
        this.messageStore = messageStore;
        EnvConfig cfg = EnvConfig.get();
        int timeoutMinutes = cfg.getInt(EnvKey.SESSION_TIMEOUT_MINUTES, 30);
        this.timeout = Duration.ofMinutes(timeoutMinutes);
        this.minMessages = cfg.getInt(EnvKey.MEMORY_MIN_MESSAGES, 5);
        this.minUserChars = cfg.getInt(EnvKey.MEMORY_MIN_USER_CHARS, 100);
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
     */
    private List<String> closeTimedOutSessions(String userId) {
        List<Session> activeSessions = sessionStore.findActiveByUser(userId);
        List<String> timedOutIds = new ArrayList<>();
        for (Session s : activeSessions) {
            if (isTimedOut(s)) {
                sessionStore.close(s.id(), Session.SessionStatus.timeout);
                timedOutIds.add(s.id());
                log.info("Closed timed-out session {} for user {} (lastActive={})", s.id(), userId, s.lastActive());
            }
        }
        return timedOutIds;
    }

    private boolean isTimedOut(Session session) {
        return session.lastActive().plus(timeout).isBefore(java.time.Instant.now());
    }

    /**
     * Check if a session meets quality thresholds for preference refinement.
     */
    public boolean isWorthyOfRefinement(String sessionId) {
        int msgCount = messageStore.countUserMessages(sessionId);
        int charCount = messageStore.sumUserContentLength(sessionId);
        boolean worthy = msgCount >= minMessages && charCount >= minUserChars;
        if (!worthy) {
            log.debug("Session {} not worthy of refinement: msgs={}/{}, chars={}/{}",
                    sessionId, msgCount, minMessages, charCount, minUserChars);
        }
        return worthy;
    }

    public Duration getTimeout() {
        return timeout;
    }
}

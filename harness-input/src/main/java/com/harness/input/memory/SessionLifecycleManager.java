package com.harness.input.memory;

import com.harness.core.model.Session;
import com.harness.core.model.SessionCursor;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
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
    private final Duration timeout;

    public SessionLifecycleManager(SessionStore sessionStore) {
        this.sessionStore = sessionStore;
        EnvConfig cfg = EnvConfig.get();
        int timeoutMinutes = cfg.getInt(EnvKey.SESSION_TIMEOUT_MINUTES, 30);
        this.timeout = Duration.ofMinutes(timeoutMinutes);
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
    public LifecycleResult process(String userId, String tenantId, String requestedSessionId) {
        // Close timed-out sessions for this user
        List<String> timedOutIds = closeTimedOutSessions(userId, tenantId);

        // Resolve current session
        Session session;
        boolean isNew = false;

        if (requestedSessionId != null && !requestedSessionId.isBlank()) {
            // Try to find the requested session (active first)
            session = sessionStore.findActiveByOwner(
                    requestedSessionId, userId, tenantId).orElse(null);
            if (session == null) {
                // Not active — check if it exists (closed/timed-out) and reopen
                session = sessionStore.findByIdAndOwner(
                        requestedSessionId, userId, tenantId).orElse(null);
                if (session != null) {
                    sessionStore.updateLastActive(requestedSessionId);
                    log.debug("Reopened closed session {} for user {}", requestedSessionId, userId);
                } else {
                    // Truly doesn't exist, create new
                    session = sessionStore.create(userId, tenantId);
                    isNew = true;
                    log.debug("Requested session {} not found, created new {}", requestedSessionId, session.id());
                }
            }
        } else {
            // No session requested, create new
            session = sessionStore.create(userId, tenantId);
            isNew = true;
            log.debug("No session requested, created new {} for user {}", session.id(), userId);
        }

        return new LifecycleResult(session, isNew, timedOutIds);
    }

    /**
     * Close all timed-out sessions for a user and return their IDs.
     */
    private List<String> closeTimedOutSessions(String userId, String tenantId) {
        List<String> timedOutIds = new ArrayList<>();
        SessionCursor cursor = null;
        do {
            var page = sessionStore.findTimedOutByOwner(
                    userId, tenantId, timeout, cursor, 100);
            for (Session s : page.items()) {
                sessionStore.close(s.id(), Session.SessionStatus.timeout);
                timedOutIds.add(s.id());
                log.debug("Closed timed-out session {} for user {} (lastActive={})", s.id(), userId, s.lastActive());
            }
            if (!page.pageInfo().hasMore()) {
                break;
            }
            Session last = page.items().getLast();
            cursor = new SessionCursor(last.lastActive(), last.id());
        } while (true);
        return timedOutIds;
    }

    public Duration getTimeout() {
        return timeout;
    }
}

package com.harness.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import com.harness.core.model.CancellationToken;

/**
 * Dispatches session resume requests when sub-agents complete.
 * Ensures serial execution per session (no concurrent ReAct runs for the same session).
 */
public class SessionResumeDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SessionResumeDispatcher.class);

    /**
     * Callback interface for performing the actual session resume.
     */
    public interface SessionResumeCallback {
        /**
         * Resume a session by running a new ReAct loop with the given events.
         *
         * @param sessionId the session to resume
         * @param events the completed sub-agent events to process
         */
        void resume(String sessionId, List<SessionInbox.SubAgentCompletedEvent> events);
    }

    private final SessionInbox inbox;
    private final SessionResumeCallback callback;
    private final ExecutorService executor;

    // Foreground requests and background continuations share one execution gate.
    private final Map<String, SessionRunState> sessionLocks = new HashMap<>();
    private final Set<String> deferredResumes = ConcurrentHashMap.newKeySet();

    // Track active resumes
    private final Set<String> activeResumes = ConcurrentHashMap.newKeySet();
    private final Set<String> suppressedSessions = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingSuppressions = ConcurrentHashMap.newKeySet();
    private final Object suppressionLock = new Object();
    private final ConcurrentHashMap<String, CancellationToken> resumeTokens = new ConcurrentHashMap<>();
    private final ScheduledExecutorService leaseRenewal;

    public SessionResumeDispatcher(SessionInbox inbox, SessionResumeCallback callback) {
        this.inbox = inbox;
        this.callback = callback;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "session-resume-dispatcher");
            t.setDaemon(true);
            return t;
        });
        this.leaseRenewal = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "session-delivery-lease"); t.setDaemon(true); return t;
        });
        long interval = Math.max(1, inbox.leaseDuration().toMillis() / 3);
        this.leaseRenewal.scheduleWithFixedDelay(() -> {
            try { inbox.renewClaims(); }
            catch (RuntimeException e) { log.error("Delivery lease renewal failed", e); }
        }, interval, interval, TimeUnit.MILLISECONDS);
        log.info("[ResumeDispatcher] Initialized");
    }

    /**
     * Request a resume for a session. If a resume is already in progress for this session,
     * the request is queued and will be processed after the current resume completes.
     *
     * @param sessionId the session to resume
     */
    public void requestResume(String sessionId) {
        if (suppressedSessions.contains(sessionId)) return;
        if (!inbox.hasPending(sessionId)) {
            log.debug("[ResumeDispatcher] No pending events for session {}", sessionId);
            return;
        }

        executor.submit(() -> {
            try { processResume(sessionId); }
            catch (RuntimeException e) { log.error("Session delivery failed for " + sessionId, e); }
        });
        log.debug("[ResumeDispatcher] Resume requested for session {}", sessionId);
    }

    /**
     * Process resume for a session. Ensures serial execution per session.
     */
    private void processResume(String sessionId) {
        try (SessionRunLease lease = tryResumeLease(sessionId)) {
            if (lease == null) return;
            if (suppressedSessions.contains(sessionId)) return;
            // Check if already resuming
            if (activeResumes.contains(sessionId)) {
                log.debug("[ResumeDispatcher] Session {} already resuming, skipping", sessionId);
                return;
            }

            // Drain pending events
            List<SessionInbox.SubAgentCompletedEvent> events = inbox.drain(sessionId);
            if (events.isEmpty()) {
                log.debug("[ResumeDispatcher] No events to process for session {}", sessionId);
                return;
            }

            // Mark as active
            activeResumes.add(sessionId);
            log.info("[ResumeDispatcher] Resuming session {} with {} events", sessionId, events.size());

            boolean completed = false;
            try {
                if (suppressedSessions.contains(sessionId)) return;
                // Perform the actual resume
                callback.resume(sessionId, events);

                // Mark events as consumed
                List<String> eventIds = events.stream()
                        .map(SessionInbox.SubAgentCompletedEvent::eventId)
                        .toList();
                if (!suppressedSessions.contains(sessionId)) inbox.markConsumed(sessionId, eventIds);
                completed = true;

                log.info("[ResumeDispatcher] Session {} resume completed", sessionId);
            } catch (Exception e) {
                log.error("[ResumeDispatcher] Session {} resume failed: {}", sessionId, e.getMessage(), e);
                // Reset events back to PENDING so they can be retried
                List<String> failedEventIds = events.stream()
                        .map(SessionInbox.SubAgentCompletedEvent::eventId)
                        .toList();
                if (!suppressedSessions.contains(sessionId)) inbox.resetToPending(sessionId, failedEventIds);
                log.info("[ResumeDispatcher] Reset {} events to PENDING for retry", failedEventIds.size());
            } finally {
                activeResumes.remove(sessionId);

                // Check if more events arrived during resume
                if (completed && !suppressedSessions.contains(sessionId) && inbox.hasPending(sessionId)) {
                    log.debug("[ResumeDispatcher] More events arrived for session {}, re-queueing", sessionId);
                    requestResume(sessionId);
                }
            }
        }
    }

    public SessionRunLease acquireForeground(String sessionId, CancellationToken token) {
        if (sessionId == null || sessionId.isBlank()) return null;
        synchronized (sessionLocks) {
            SessionRunState state = sessionLocks.computeIfAbsent(sessionId, key -> new SessionRunState());
            state.waiters++;
            try {
                while (state.running) {
                    if (token != null && token.isCancelled()) throw new CancellationException("Request cancelled");
                    sessionLocks.wait(100);
                }
                if (token != null && token.isCancelled()) throw new CancellationException("Request cancelled");
                state.running = true;
                return new SessionRunLease(sessionId, state);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Session execution wait interrupted");
            } finally {
                state.waiters--;
                if (!state.running && state.waiters == 0) sessionLocks.remove(sessionId, state);
            }
        }
    }

    private SessionRunLease tryResumeLease(String sessionId) {
        synchronized (sessionLocks) {
            SessionRunState state = sessionLocks.computeIfAbsent(sessionId, key -> new SessionRunState());
            if (state.running || state.waiters > 0) {
                deferredResumes.add(sessionId);
                return null;
            }
            deferredResumes.remove(sessionId);
            state.running = true;
            return new SessionRunLease(sessionId, state);
        }
    }

    private static final class SessionRunState {
        boolean running;
        int waiters;
    }

    public final class SessionRunLease implements AutoCloseable {
        private final String sessionId;
        private final SessionRunState state;
        private boolean closed;

        private SessionRunLease(String sessionId, SessionRunState state) {
            this.sessionId = sessionId;
            this.state = state;
        }

        public String sessionId() { return sessionId; }

        @Override public void close() {
            boolean deferred;
            synchronized (sessionLocks) {
                if (closed) return;
                closed = true;
                state.running = false;
                if (state.waiters == 0) sessionLocks.remove(sessionId, state);
                deferred = deferredResumes.remove(sessionId);
                sessionLocks.notifyAll();
            }
            if (deferred) {
                try { requestResume(sessionId); }
                catch (RuntimeException e) { log.error("Deferred session delivery pending for " + sessionId, e); }
            }
        }
    }

    public void registerResumeToken(String sessionId, CancellationToken token) {
        resumeTokens.put(sessionId, token);
        if (suppressedSessions.contains(sessionId)) token.cancel();
    }

    public void unregisterResumeToken(String sessionId, CancellationToken token) { resumeTokens.remove(sessionId, token); }

    public void allowSession(String sessionId) {
        synchronized (suppressionLock) {
            if (pendingSuppressions.contains(sessionId)) {
                inbox.suppressSession(sessionId);
                pendingSuppressions.remove(sessionId);
            }
            suppressedSessions.remove(sessionId);
        }
    }

    public boolean cancelSession(String sessionId) {
        suppressedSessions.add(sessionId);
        CancellationToken token = resumeTokens.get(sessionId);
        if (token != null) token.cancel();
        synchronized (suppressionLock) {
            try {
                boolean active = token != null || activeResumes.contains(sessionId) || inbox.hasPending(sessionId);
                inbox.suppressSession(sessionId);
                pendingSuppressions.remove(sessionId);
                return active;
            } catch (RuntimeException failure) {
                pendingSuppressions.add(sessionId);
                throw failure;
            }
        }
    }

    public void retryPendingSuppressions() {
        for (String sessionId : pendingSuppressions) {
            synchronized (suppressionLock) {
                if (!pendingSuppressions.contains(sessionId)) continue;
                try {
                    inbox.suppressSession(sessionId);
                    pendingSuppressions.remove(sessionId);
                } catch (RuntimeException e) { log.error("Session suppression persistence pending for " + sessionId, e); }
            }
        }
    }

    /**
     * Shutdown the dispatcher.
     */
    public void shutdown() {
        resumeTokens.values().forEach(CancellationToken::cancel);
        leaseRenewal.shutdownNow();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        deferredResumes.clear();
        log.info("[ResumeDispatcher] Shut down");
    }
}

package com.harness.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

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

    // Per-session locks to ensure serial resume
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

    // Track active resumes
    private final Set<String> activeResumes = ConcurrentHashMap.newKeySet();
    private final AtomicInteger pendingResumes = new AtomicInteger(0);

    public SessionResumeDispatcher(SessionInbox inbox, SessionResumeCallback callback) {
        this.inbox = inbox;
        this.callback = callback;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "session-resume-dispatcher");
            t.setDaemon(true);
            return t;
        });
        log.info("[ResumeDispatcher] Initialized");
    }

    /**
     * Request a resume for a session. If a resume is already in progress for this session,
     * the request is queued and will be processed after the current resume completes.
     *
     * @param sessionId the session to resume
     */
    public void requestResume(String sessionId) {
        if (!inbox.hasPending(sessionId)) {
            log.debug("[ResumeDispatcher] No pending events for session {}", sessionId);
            return;
        }

        pendingResumes.incrementAndGet();
        executor.submit(() -> processResume(sessionId));
        log.debug("[ResumeDispatcher] Resume requested for session {}", sessionId);
    }

    /**
     * Process resume for a session. Ensures serial execution per session.
     */
    private void processResume(String sessionId) {
        Object lock = sessionLocks.computeIfAbsent(sessionId, k -> new Object());

        synchronized (lock) {
            // Check if already resuming
            if (activeResumes.contains(sessionId)) {
                log.debug("[ResumeDispatcher] Session {} already resuming, skipping", sessionId);
                pendingResumes.decrementAndGet();
                return;
            }

            // Drain pending events
            List<SessionInbox.SubAgentCompletedEvent> events = inbox.drain(sessionId);
            if (events.isEmpty()) {
                log.debug("[ResumeDispatcher] No events to process for session {}", sessionId);
                pendingResumes.decrementAndGet();
                return;
            }

            // Mark as active
            activeResumes.add(sessionId);
            log.info("[ResumeDispatcher] Resuming session {} with {} events", sessionId, events.size());

            try {
                // Perform the actual resume
                callback.resume(sessionId, events);

                // Mark events as consumed
                List<String> eventIds = events.stream()
                        .map(SessionInbox.SubAgentCompletedEvent::eventId)
                        .toList();
                inbox.markConsumed(sessionId, eventIds);

                log.info("[ResumeDispatcher] Session {} resume completed", sessionId);
            } catch (Exception e) {
                log.error("[ResumeDispatcher] Session {} resume failed: {}", sessionId, e.getMessage(), e);
                // Reset events back to PENDING so they can be retried
                List<String> failedEventIds = events.stream()
                        .map(SessionInbox.SubAgentCompletedEvent::eventId)
                        .toList();
                inbox.resetToPending(sessionId, failedEventIds);
                log.info("[ResumeDispatcher] Reset {} events to PENDING for retry", failedEventIds.size());
            } finally {
                activeResumes.remove(sessionId);
                pendingResumes.decrementAndGet();

                // Check if more events arrived during resume
                if (inbox.hasPending(sessionId)) {
                    log.debug("[ResumeDispatcher] More events arrived for session {}, re-queueing", sessionId);
                    requestResume(sessionId);
                }
            }
        }
    }

    /**
     * Clean up session locks for sessions that are no longer active.
     * Should be called periodically to prevent memory leaks.
     */
    public void cleanupSessionLocks() {
        int before = sessionLocks.size();
        sessionLocks.entrySet().removeIf(entry ->
                !activeResumes.contains(entry.getKey()) && !inbox.hasPending(entry.getKey()));
        int removed = before - sessionLocks.size();
        if (removed > 0) {
            log.debug("[ResumeDispatcher] Cleaned up {} session locks", removed);
        }
    }

    /**
     * Shutdown the dispatcher.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        sessionLocks.clear();
        log.info("[ResumeDispatcher] Shut down");
    }
}

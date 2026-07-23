package com.harness.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Inbox for session-level events (e.g., sub-agent completion).
 * Events are queued per session and drained when a resume is triggered.
 */
public class SessionInbox {

    private static final Logger log = LoggerFactory.getLogger(SessionInbox.class);

    /**
     * Represents a sub-agent completion event.
     */
    public record SubAgentCompletedEvent(
            String eventId,
            String sessionId,
            String taskId,
            String taskDescription,
            SubAgentResult result,
            Instant timestamp,
            EventStatus status
    ) {
        public enum EventStatus {
            PENDING,
            PROCESSING,
            CONSUMED
        }
    }

    // Per-session event queue
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SubAgentCompletedEvent>> inboxes = new ConcurrentHashMap<>();

    /**
     * Submit a sub-agent completion event to the session inbox.
     */
    public void submit(SubAgentCompletedEvent event) {
        String sessionId = event.sessionId();
        inboxes.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(event);
        log.debug("[SessionInbox] Event submitted: sessionId={}, taskId={}, eventId={}",
                sessionId, event.taskId(), event.eventId());
    }

    /**
     * Drain all pending events for a session.
     * Atomically moves events from PENDING to PROCESSING status.
     * Uses compute() for atomicity — no concurrent submit() can be lost.
     */
    public List<SubAgentCompletedEvent> drain(String sessionId) {
        List<SubAgentCompletedEvent> pending = new ArrayList<>();

        inboxes.compute(sessionId, (key, inbox) -> {
            if (inbox == null || inbox.isEmpty()) {
                return inbox;
            }

            CopyOnWriteArrayList<SubAgentCompletedEvent> updated = new CopyOnWriteArrayList<>();
            for (SubAgentCompletedEvent event : inbox) {
                if (event.status() == SubAgentCompletedEvent.EventStatus.PENDING) {
                    pending.add(new SubAgentCompletedEvent(
                            event.eventId(), event.sessionId(), event.taskId(),
                            event.taskDescription(), event.result(), event.timestamp(),
                            SubAgentCompletedEvent.EventStatus.PROCESSING
                    ));
                } else {
                    updated.add(event);
                }
            }
            // Add PROCESSING events back
            updated.addAll(pending);
            return updated;
        });

        log.debug("[SessionInbox] Drained {} events for session {}", pending.size(), sessionId);
        return pending;
    }

    /**
     * Mark events as consumed after successful resume.
     * Uses compute() for atomicity — no concurrent submit() can be lost.
     */
    public void markConsumed(String sessionId, List<String> eventIds) {
        Set<String> consumedIds = new HashSet<>(eventIds);

        inboxes.compute(sessionId, (key, inbox) -> {
            if (inbox == null) {
                return null;
            }

            CopyOnWriteArrayList<SubAgentCompletedEvent> updated = new CopyOnWriteArrayList<>();
            for (SubAgentCompletedEvent event : inbox) {
                if (consumedIds.contains(event.eventId())) {
                    updated.add(new SubAgentCompletedEvent(
                            event.eventId(), event.sessionId(), event.taskId(),
                            event.taskDescription(), event.result(), event.timestamp(),
                            SubAgentCompletedEvent.EventStatus.CONSUMED
                    ));
                } else {
                    updated.add(event);
                }
            }
            return updated;
        });

        log.debug("[SessionInbox] Marked {} events as consumed for session {}", eventIds.size(), sessionId);
    }

    /**
     * Check if a session has pending events.
     */
    public boolean hasPending(String sessionId) {
        CopyOnWriteArrayList<SubAgentCompletedEvent> inbox = inboxes.get(sessionId);
        if (inbox == null) {
            return false;
        }
        return inbox.stream().anyMatch(e -> e.status() == SubAgentCompletedEvent.EventStatus.PENDING);
    }

    /**
     * Reset PROCESSING events back to PENDING for error recovery.
     * Called when resumeSession fails so events can be retried.
     */
    public void resetToPending(String sessionId, List<String> eventIds) {
        Set<String> resetIds = new HashSet<>(eventIds);

        inboxes.compute(sessionId, (key, inbox) -> {
            if (inbox == null) {
                return null;
            }

            CopyOnWriteArrayList<SubAgentCompletedEvent> updated = new CopyOnWriteArrayList<>();
            for (SubAgentCompletedEvent event : inbox) {
                if (resetIds.contains(event.eventId()) && event.status() == SubAgentCompletedEvent.EventStatus.PROCESSING) {
                    updated.add(new SubAgentCompletedEvent(
                            event.eventId(), event.sessionId(), event.taskId(),
                            event.taskDescription(), event.result(), event.timestamp(),
                            SubAgentCompletedEvent.EventStatus.PENDING
                    ));
                } else {
                    updated.add(event);
                }
            }
            return updated;
        });

        log.debug("[SessionInbox] Reset {} events to PENDING for session {}", eventIds.size(), sessionId);
    }

    /**
     * Clean up consumed events older than the specified age.
     * Removes empty inbox entries to prevent unbounded map growth.
     */
    public void cleanup(Instant maxAge) {
        for (Map.Entry<String, CopyOnWriteArrayList<SubAgentCompletedEvent>> entry : inboxes.entrySet()) {
            entry.getValue().removeIf(e ->
                e.status() == SubAgentCompletedEvent.EventStatus.CONSUMED &&
                e.timestamp().isBefore(maxAge)
            );
            if (entry.getValue().isEmpty()) {
                inboxes.remove(entry.getKey());
            }
        }
    }
}

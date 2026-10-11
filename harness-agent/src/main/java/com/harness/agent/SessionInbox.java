package com.harness.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.time.Duration;
import com.harness.agent.subagent.SubAgentTaskRepository;

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
            String parentTurnId,
            SubAgentResult result,
            Instant timestamp,
            EventStatus status,
            AgentRunContext.Owner owner,
            String leaseToken
    ) {
        public SubAgentCompletedEvent(String eventId, String sessionId, String taskId,
                                     String taskDescription, String parentTurnId, SubAgentResult result,
                                     Instant timestamp, EventStatus status, AgentRunContext.Owner owner) {
            this(eventId, sessionId, taskId, taskDescription, parentTurnId, result, timestamp, status, owner, null);
        }
        public enum EventStatus {
            PENDING,
            PROCESSING,
            CONSUMED
        }
    }

    // Per-session event queue
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SubAgentCompletedEvent>> inboxes = new ConcurrentHashMap<>();
    private final SubAgentTaskRepository repository;
    private final Duration lease;
    private final ConcurrentHashMap<String, SubAgentCompletedEvent> claims = new ConcurrentHashMap<>();

    public SessionInbox() { this(null, Duration.ofMinutes(1)); }

    public SessionInbox(SubAgentTaskRepository repository, Duration lease) {
        this.repository = repository;
        if (lease == null || lease.isZero() || lease.isNegative()) throw new IllegalArgumentException("lease must be positive");
        this.lease = lease;
    }

    public boolean isPersistent() { return repository != null; }
    public Duration leaseDuration() { return lease; }
    public void renewClaims() {
        if (repository != null) claims.values().forEach(event -> repository.renewDelivery(event.eventId(), event.leaseToken(), lease));
    }

    public void suppressSession(String sessionId) {
        if (repository != null) repository.suppressSession(sessionId);
        inboxes.remove(sessionId);
        claims.entrySet().removeIf(entry -> entry.getValue().sessionId().equals(sessionId));
    }

    /**
     * Submit a sub-agent completion event to the session inbox.
     * Uses compute() for atomicity — no concurrent drain() can lose the event.
     */
    public void submit(SubAgentCompletedEvent event) {
        if (repository != null) return; // Completion already registered its event in the task transaction.
        String sessionId = event.sessionId();
        inboxes.compute(sessionId, (key, list) -> {
            if (list == null) list = new CopyOnWriteArrayList<>();
            list.add(event);
            return list;
        });
        log.debug("[SessionInbox] Event submitted: sessionId={}, taskId={}, eventId={}",
                sessionId, event.taskId(), event.eventId());
    }

    /**
     * Drain all pending events for a session.
     * Atomically moves events from PENDING to PROCESSING status.
     * Uses compute() for atomicity — no concurrent submit() can be lost.
     */
    public List<SubAgentCompletedEvent> drain(String sessionId) {
        if (repository != null) {
            var events = repository.claimDeliveries(sessionId, lease, 100);
            events.forEach(event -> claims.put(event.eventId(), event));
            return events;
        }
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
                            event.taskDescription(), event.parentTurnId(), event.result(), event.timestamp(),
                            SubAgentCompletedEvent.EventStatus.PROCESSING, event.owner()
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
        if (repository != null) {
            for (String id : eventIds) {
                var event = claims.get(id);
                if (event != null && event.sessionId().equals(sessionId)) {
                    repository.acknowledgeDelivery(id, event.leaseToken());
                    claims.remove(id, event);
                }
            }
            return;
        }
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
                            event.taskDescription(), event.parentTurnId(), event.result(), event.timestamp(),
                            SubAgentCompletedEvent.EventStatus.CONSUMED, event.owner()
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
        if (repository != null) {
            return repository.hasPendingDelivery(sessionId);
        }
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
        if (repository != null) {
            for (String id : eventIds) {
                var event = claims.get(id);
                if (event != null && event.sessionId().equals(sessionId)) {
                    repository.releaseDelivery(id, event.leaseToken());
                    claims.remove(id, event);
                }
            }
            return;
        }
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
                            event.taskDescription(), event.parentTurnId(), event.result(), event.timestamp(),
                            SubAgentCompletedEvent.EventStatus.PENDING, event.owner()
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

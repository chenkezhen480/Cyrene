package com.harness.agent.subagent;

import com.harness.agent.*;
import com.harness.core.model.PageResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Explicit ephemeral repository for unit fixtures and embedded callers. */
public final class InMemorySubAgentTaskRepository implements SubAgentTaskRepository {
    private final Duration retention;
    private final Map<String, StoredTask> tasks = new HashMap<>();
    private final Map<String, String> leaseTokens = new HashMap<>();
    private final Map<String, Instant> leases = new HashMap<>();

    public InMemorySubAgentTaskRepository(Duration retention) {
        if (retention == null || retention.isNegative() || retention.isZero()) throw new IllegalArgumentException("retention must be positive");
        this.retention = retention;
    }

    @Override public synchronized void create(SubAgentTaskRecord record) {
        var task = new StoredTask(record.taskId(), record.ownerRunId(), record.ownerSessionId(), record.ownerTurnId(),
                record.owner(), record.spawnToolCallId(), record.rootTraceId(), record.task(), record.status().get(), null,
                record.deliveryState().get(), null, record.createdAt(), record.createdAt().plus(retention));
        if (tasks.putIfAbsent(record.taskId(), task) != null) throw new IllegalStateException("Duplicate task ID");
    }

    @Override public synchronized boolean transition(String taskId, SubAgentStatus expected, SubAgentStatus next) {
        var task = required(taskId);
        if (task.status() != expected) return false;
        tasks.put(taskId, update(task, next, task.result(), task.deliveryState(), task.eventId()));
        return true;
    }

    @Override public synchronized boolean complete(String taskId, SubAgentStatus status, SubAgentResult result) {
        var task = required(taskId);
        if (task.status().isTerminal()) return false;
        if (!status.isTerminal() || result.status() != status || !taskId.equals(result.taskId())) throw new IllegalArgumentException("Invalid terminal result");
        tasks.put(taskId, update(task, status, result, task.deliveryState(), "subagent:" + taskId));
        return true;
    }

    @Override public synchronized boolean changeDelivery(String taskId, ResultDeliveryState expected, ResultDeliveryState next) {
        var task = required(taskId);
        if (task.deliveryState() != expected || next == ResultDeliveryState.INLINE_CONSUMED && task.result() == null) return false;
        tasks.put(taskId, update(task, task.status(), task.result(), next, task.eventId()));
        return true;
    }

    @Override public synchronized Optional<StoredTask> findAuthorized(AgentRunContext.Owner owner, String sessionId, String taskId) {
        return Optional.ofNullable(tasks.get(taskId)).filter(task -> authorized(task, owner, sessionId));
    }

    @Override public synchronized PageResponse<StoredTask> listAuthorized(AgentRunContext.Owner owner, String sessionId, String cursor, int limit) {
        SubAgentTaskRepository.requireLimit(limit);
        String[] after = SubAgentTaskRepository.decodeCursor(cursor);
        var fetched = tasks.values().stream().filter(task -> authorized(task, owner, sessionId))
                .filter(task -> after == null || task.createdAt().toEpochMilli() > Long.parseLong(after[0])
                        || task.createdAt().toEpochMilli() == Long.parseLong(after[0]) && task.taskId().compareTo(after[1]) > 0)
                .sorted(Comparator.comparing(StoredTask::createdAt).thenComparing(StoredTask::taskId)).limit(limit + 1L).toList();
        return PageResponse.fromFetched(fetched, limit, SubAgentTaskRepository::cursor);
    }

    @Override public synchronized List<SessionInbox.SubAgentCompletedEvent> claimDeliveries(String sessionId, Duration lease, int limit) {
        SubAgentTaskRepository.requireLimit(limit);
        Instant now = Instant.now();
        var pending = tasks.values().stream().filter(task -> Objects.equals(sessionId, task.ownerSessionId()))
                .filter(task -> task.result() != null && (task.deliveryState() == ResultDeliveryState.DETACHED
                        || task.deliveryState() == ResultDeliveryState.DELIVERY_CLAIMED && leases.get(task.eventId()).isBefore(now)))
                .sorted(Comparator.comparing(StoredTask::createdAt).thenComparing(StoredTask::taskId)).limit(limit).toList();
        var events = new ArrayList<SessionInbox.SubAgentCompletedEvent>();
        for (var task : pending) {
            String token = UUID.randomUUID().toString();
            tasks.put(task.taskId(), update(task, task.status(), task.result(), ResultDeliveryState.DELIVERY_CLAIMED, task.eventId()));
            leases.put(task.eventId(), now.plus(lease));
            leaseTokens.put(task.eventId(), token);
            events.add(task.event(token));
        }
        return List.copyOf(events);
    }

    @Override public synchronized void acknowledgeDelivery(String eventId, String leaseToken) { finishLease(eventId, leaseToken, ResultDeliveryState.SESSION_RESUMED); }
    @Override public synchronized void releaseDelivery(String eventId, String leaseToken) { finishLease(eventId, leaseToken, ResultDeliveryState.DETACHED); }
    @Override public synchronized void renewDelivery(String eventId, String leaseToken, Duration lease) {
        if (Objects.equals(leaseTokens.get(eventId), leaseToken)) leases.put(eventId, Instant.now().plus(lease));
    }

    private void finishLease(String eventId, String token, ResultDeliveryState next) {
        if (!Objects.equals(leaseTokens.get(eventId), token)) return;
        tasks.values().stream().filter(task -> Objects.equals(eventId, task.eventId()) && task.deliveryState() == ResultDeliveryState.DELIVERY_CLAIMED).findFirst()
                .ifPresent(task -> tasks.put(task.taskId(), update(task, task.status(), task.result(), next, task.eventId())));
        leases.remove(eventId);
        leaseTokens.remove(eventId);
    }

    @Override public synchronized PageResponse<String> pendingSessions(String cursor, int limit) {
        SubAgentTaskRepository.requireLimit(limit);
        var sessions = tasks.values().stream().filter(task -> task.ownerSessionId() != null && task.result() != null)
                .filter(task -> task.deliveryState() == ResultDeliveryState.DETACHED
                        || task.deliveryState() == ResultDeliveryState.DELIVERY_CLAIMED && leases.get(task.eventId()).isBefore(Instant.now()))
                .map(StoredTask::ownerSessionId).distinct().filter(id -> cursor == null || id.compareTo(cursor) > 0)
                .sorted().limit(limit + 1L).toList();
        return PageResponse.fromFetched(sessions, limit, id -> id);
    }

    @Override public synchronized boolean hasPendingDelivery(String sessionId) {
        return tasks.values().stream().anyMatch(task -> Objects.equals(sessionId, task.ownerSessionId()) && task.result() != null
                && (task.deliveryState() == ResultDeliveryState.DETACHED
                || task.deliveryState() == ResultDeliveryState.DELIVERY_CLAIMED && leases.get(task.eventId()).isBefore(Instant.now())));
    }

    @Override public synchronized int suppressSession(String sessionId) {
        int changed = (int) tasks.values().stream().filter(task -> Objects.equals(sessionId, task.ownerSessionId())
                && task.deliveryState() != ResultDeliveryState.INLINE_CONSUMED && task.deliveryState() != ResultDeliveryState.SESSION_RESUMED
                && task.deliveryState() != ResultDeliveryState.SUPPRESSED).count();
        tasks.replaceAll((id, task) -> Objects.equals(sessionId, task.ownerSessionId())
                && task.deliveryState() != ResultDeliveryState.INLINE_CONSUMED && task.deliveryState() != ResultDeliveryState.SESSION_RESUMED
                ? update(task, task.status(), task.result(), ResultDeliveryState.SUPPRESSED, task.eventId()) : task);
        return changed;
    }

    @Override public synchronized void suppressTask(String taskId) {
        var task = required(taskId);
        if (task.deliveryState() != ResultDeliveryState.INLINE_CONSUMED && task.deliveryState() != ResultDeliveryState.SESSION_RESUMED)
            tasks.put(taskId, update(task, task.status(), task.result(), ResultDeliveryState.SUPPRESSED, task.eventId()));
    }

    @Override public synchronized int interruptUnfinished(int limit) {
        SubAgentTaskRepository.requireLimit(limit);
        var unfinished = tasks.values().stream().filter(task -> !task.status().isTerminal()).limit(limit).toList();
        for (var task : unfinished) {
            var result = new SubAgentResult(task.taskId(), null, "Task interrupted by process restart", false, SubAgentStatus.INTERRUPTED,
                    List.of(), ToolExecutionSummary.empty(), ContractValidation.notEvaluated(task.task().completionContract() != null), null, 0, null);
            complete(task.taskId(), SubAgentStatus.INTERRUPTED, result);
        }
        tasks.replaceAll((id, task) -> task.deliveryState() == ResultDeliveryState.INLINE_PENDING
                ? update(task, task.status(), task.result(), ResultDeliveryState.DETACHED, task.eventId()) : task);
        return unfinished.size();
    }

    @Override public synchronized int deleteExpired(int limit) {
        SubAgentTaskRepository.requireLimit(limit);
        var expired = tasks.values().stream().filter(task -> task.status().isTerminal() && task.expiresAt().isBefore(Instant.now()))
                .sorted(Comparator.comparing(StoredTask::expiresAt).thenComparing(StoredTask::taskId)).limit(limit).map(StoredTask::taskId).toList();
        expired.forEach(tasks::remove);
        return expired.size();
    }

    private static boolean authorized(StoredTask task, AgentRunContext.Owner owner, String sessionId) {
        return Objects.equals(sessionId, task.ownerSessionId()) && owner != null && task.owner() != null
                && Objects.equals(owner.userId(), task.owner().userId())
                && SubAgentTaskRepository.tenant(owner).equals(SubAgentTaskRepository.tenant(task.owner()))
                && task.expiresAt().isAfter(Instant.now());
    }
    private StoredTask required(String id) { return Objects.requireNonNull(tasks.get(id), "Task not persisted: " + id); }
    private StoredTask update(StoredTask task, SubAgentStatus status, SubAgentResult result, ResultDeliveryState delivery, String eventId) {
        return new StoredTask(task.taskId(), task.ownerRunId(), task.ownerSessionId(), task.ownerTurnId(), task.owner(), task.spawnToolCallId(), task.rootTraceId(),
                task.task(), status, result, delivery, eventId, task.createdAt(), task.expiresAt());
    }
}

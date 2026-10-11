package com.harness.agent;

import com.harness.core.model.CancellationToken;
import com.harness.agent.subagent.SubAgentTaskRepository;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Record of a sub-agent task within a run scope.
 * Tracks task metadata, completion future, lifecycle status, cancellation,
 * and result delivery state.
 *
 * Two independent state machines:
 * - SubAgentStatus: QUEUED → RUNNING → SUCCEEDED/INCOMPLETE/FAILED/CANCELLED/TIMED_OUT
 * - ResultDeliveryState: INLINE_PENDING → INLINE_CONSUMED/DETACHED → SESSION_RESUMED
 */
public class SubAgentTaskRecord {

    private final String taskId;
    private final String ownerRunId;
    private final String spawnToolCallId;
    private final String rootTraceId;
    private final SubAgentTaskRepository repository;
    private final String ownerSessionId;
    private final String ownerTurnId;
    private final AgentRunContext.Owner owner;
    private final SubAgentTask task;
    private final CompletableFuture<SubAgentResult> completion;
    private final AtomicReference<SubAgentStatus> status;
    private final AtomicReference<ResultDeliveryState> deliveryState;
    private final Instant createdAt;
    private final CancellationToken taskCancellationToken;
    private final AtomicBoolean lifecycleTerminalPublished = new AtomicBoolean();
    private final AtomicBoolean completionEventRequested = new AtomicBoolean();

    // Stored result for detached delivery (set by completion callback)
    private volatile SubAgentResult storedResult;
    private volatile SubAgentResult pendingTerminalResult;
    private volatile RuntimeException persistenceFailure;

    public SubAgentTaskRecord(String taskId, String ownerRunId, String ownerSessionId, SubAgentTask task, CancellationToken taskCancellationToken) {
        this(taskId, ownerRunId, ownerSessionId, ownerRunId, task, taskCancellationToken);
    }

    public SubAgentTaskRecord(
            String taskId,
            String ownerRunId,
            String ownerSessionId,
            String ownerTurnId,
            SubAgentTask task,
            CancellationToken taskCancellationToken
    ) {
        this(taskId, ownerRunId, ownerSessionId, ownerTurnId, task, taskCancellationToken, null);
    }

    public SubAgentTaskRecord(
            String taskId, String ownerRunId, String ownerSessionId, String ownerTurnId,
            SubAgentTask task, CancellationToken taskCancellationToken, AgentRunContext.Owner owner
    ) {
        this(taskId, ownerRunId, ownerSessionId, ownerTurnId, task, taskCancellationToken, owner, null, null, null);
    }

    public SubAgentTaskRecord(
            String taskId, String ownerRunId, String ownerSessionId, String ownerTurnId,
            SubAgentTask task, CancellationToken taskCancellationToken, AgentRunContext.Owner owner,
            String spawnToolCallId, String rootTraceId, SubAgentTaskRepository repository
    ) {
        this.taskId = taskId;
        this.ownerRunId = ownerRunId;
        this.spawnToolCallId = spawnToolCallId;
        this.rootTraceId = rootTraceId;
        this.repository = repository;
        this.ownerSessionId = ownerSessionId;
        this.ownerTurnId = ownerTurnId;
        this.owner = owner;
        this.task = task;
        this.completion = new CompletableFuture<>();
        this.status = new AtomicReference<>(SubAgentStatus.QUEUED);
        this.deliveryState = new AtomicReference<>(ResultDeliveryState.INLINE_PENDING);
        this.createdAt = Instant.ofEpochMilli(System.currentTimeMillis());
        this.taskCancellationToken = taskCancellationToken;
    }

    public String taskId() { return taskId; }
    public String ownerRunId() { return ownerRunId; }
    public String spawnToolCallId() { return spawnToolCallId; }
    public String rootTraceId() { return rootTraceId; }
    public String ownerSessionId() { return ownerSessionId; }
    public String ownerTurnId() { return ownerTurnId; }
    public AgentRunContext.Owner owner() { return owner; }
    public SubAgentTask task() { return task; }
    public CompletableFuture<SubAgentResult> completion() { return completion; }
    public AtomicReference<SubAgentStatus> status() { return status; }
    public AtomicReference<ResultDeliveryState> deliveryState() { return deliveryState; }
    public Instant createdAt() { return createdAt; }
    public CancellationToken taskCancellationToken() { return taskCancellationToken; }
    public SubAgentResult storedResult() { return storedResult; }
    public SubAgentResult pendingTerminalResult() { return pendingTerminalResult; }
    public RuntimeException persistenceFailure() { return persistenceFailure; }

    public boolean markLifecycleTerminalPublished() {
        return lifecycleTerminalPublished.compareAndSet(false, true);
    }

    public boolean markCompletionEventRequested() { return completionEventRequested.compareAndSet(false, true); }

    // Publish the result before its terminal status, and complete each task exactly once.
    public synchronized boolean start() {
        if (taskCancellationToken != null && taskCancellationToken.isCancelled()) {
            markCancelled();
            return false;
        }
        if (status.get() != SubAgentStatus.QUEUED) return false;
        if (repository != null && !repository.transition(taskId, SubAgentStatus.QUEUED, SubAgentStatus.RUNNING)) return false;
        status.set(SubAgentStatus.RUNNING);
        return true;
    }

    public synchronized void succeed(SubAgentResult result) {
        if (isCancelRequested() || taskCancellationToken != null && taskCancellationToken.isCancelled()) {
            markCancelled();
        } else {
            finish(SubAgentStatus.SUCCEEDED, result);
        }
    }

    public synchronized void fail(SubAgentResult result) {
        if (isCancelRequested() || taskCancellationToken != null && taskCancellationToken.isCancelled()) {
            markCancelled();
        } else {
            finish(SubAgentStatus.FAILED, result);
        }
    }

    public synchronized void markIncomplete(SubAgentResult result) {
        if (isCancelRequested() || taskCancellationToken != null && taskCancellationToken.isCancelled()) {
            markCancelled();
        } else {
            finish(SubAgentStatus.INCOMPLETE, result);
        }
    }

    public boolean requestCancel() {
        if (taskCancellationToken != null) taskCancellationToken.cancel();
        synchronized (this) {
            if (isTerminal()) return false;
            if (pendingTerminalResult != null) return true;
            if (status.get() == SubAgentStatus.QUEUED) {
                markCancelled();
                return true;
            }
            if (repository != null && !repository.transition(taskId, status.get(), SubAgentStatus.CANCEL_REQUESTED)) return false;
            status.set(SubAgentStatus.CANCEL_REQUESTED);
            return true;
        }
    }

    public synchronized void markCancelled() {
        finish(SubAgentStatus.CANCELLED, terminalFailure(SubAgentStatus.CANCELLED, "Cancelled"));
    }

    public synchronized void markTimedOut() {
        finish(SubAgentStatus.TIMED_OUT, terminalFailure(SubAgentStatus.TIMED_OUT, "Task timed out"));
    }

    private SubAgentResult terminalFailure(SubAgentStatus terminal, String error) {
        return new SubAgentResult(taskId, null, error, false, terminal, java.util.List.of(),
                ToolExecutionSummary.empty(), ContractValidation.notEvaluated(task.completionContract() != null),
                null, 0, null);
    }

    private void finish(SubAgentStatus terminal, SubAgentResult result) {
        if (isTerminal()) return;
        if (result.status() != terminal) throw new IllegalArgumentException("Task result status mismatch");
        if (pendingTerminalResult == null) pendingTerminalResult = result;
        retryPendingCompletion();
    }

    /** Retry only persistence, never the model or tools that produced this result. */
    public synchronized void retryPendingCompletion() {
        SubAgentResult result = pendingTerminalResult;
        if (result == null) return;
        try {
            if (repository != null && !repository.complete(taskId, result.status(), result)) {
                result = repository.findAuthorized(owner, ownerSessionId, taskId)
                        .filter(task -> task.status().isTerminal() && task.result() != null)
                        .map(SubAgentTaskRepository.StoredTask::result)
                        .orElseThrow(() -> new IllegalStateException("Task terminal result was not persisted: " + taskId));
            }
            storedResult = result;
            status.set(result.status());
            pendingTerminalResult = null;
            persistenceFailure = null;
            completion.complete(result);
        } catch (RuntimeException failure) {
            persistenceFailure = failure;
            throw failure;
        }
    }

    public boolean isTerminal() { return isTerminal(status.get()); }
    public boolean isCancelRequested() { return status.get() == SubAgentStatus.CANCEL_REQUESTED; }

    // --- Delivery state transitions (CAS-based) ---

    /**
     * Detach from inline delivery. Called when the batch wait times out.
     * CAS: INLINE_PENDING → DETACHED
     */
    public synchronized boolean detach() {
        return changeDelivery(ResultDeliveryState.INLINE_PENDING, ResultDeliveryState.DETACHED);
    }

    /**
     * Consume result inline. Called when the batch result is delivered inline.
     * CAS: INLINE_PENDING → INLINE_CONSUMED
     */
    public synchronized boolean consumeInline() {
        return completion.isDone() && changeDelivery(ResultDeliveryState.INLINE_PENDING, ResultDeliveryState.INLINE_CONSUMED);
    }

    /**
     * Mark as session-resumed. Used by the legacy ephemeral inbox after event submission.
     * CAS: DETACHED → SESSION_RESUMED
     */
    public synchronized boolean markSessionResumed() {
        return changeDelivery(ResultDeliveryState.DETACHED, ResultDeliveryState.SESSION_RESUMED);
    }

    public synchronized void suppressDelivery() {
        if (repository != null) repository.suppressTask(taskId);
        if (deliveryState.get() != ResultDeliveryState.INLINE_CONSUMED && deliveryState.get() != ResultDeliveryState.SESSION_RESUMED)
            deliveryState.set(ResultDeliveryState.SUPPRESSED);
    }

    private boolean changeDelivery(ResultDeliveryState expected, ResultDeliveryState next) {
        if (deliveryState.get() != expected) return false;
        if (repository != null && !repository.changeDelivery(taskId, expected, next)) return false;
        deliveryState.set(next);
        return true;
    }

    /**
     * Check if result was detached (will go to session resume).
     */
    public boolean isDetached() {
        return deliveryState.get() == ResultDeliveryState.DETACHED;
    }

    private static boolean isTerminal(SubAgentStatus status) {
        return status.isTerminal();
    }
}

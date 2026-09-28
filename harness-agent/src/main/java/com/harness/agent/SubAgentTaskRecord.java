package com.harness.agent;

import com.harness.core.model.CancellationToken;

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
    private final String ownerSessionId;
    private final String ownerTurnId;
    private final SubAgentTask task;
    private final CompletableFuture<SubAgentResult> completion;
    private final AtomicReference<SubAgentStatus> status;
    private final AtomicReference<ResultDeliveryState> deliveryState;
    private final Instant createdAt;
    private final CancellationToken taskCancellationToken;
    private final AtomicBoolean lifecycleTerminalPublished = new AtomicBoolean();

    // Stored result for detached delivery (set by completion callback)
    private volatile SubAgentResult storedResult;

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
        this.taskId = taskId;
        this.ownerSessionId = ownerSessionId;
        this.ownerTurnId = ownerTurnId;
        this.task = task;
        this.completion = new CompletableFuture<>();
        this.status = new AtomicReference<>(SubAgentStatus.QUEUED);
        this.deliveryState = new AtomicReference<>(ResultDeliveryState.INLINE_PENDING);
        this.createdAt = Instant.now();
        this.taskCancellationToken = taskCancellationToken;
    }

    public String taskId() { return taskId; }
    public String ownerSessionId() { return ownerSessionId; }
    public String ownerTurnId() { return ownerTurnId; }
    public SubAgentTask task() { return task; }
    public CompletableFuture<SubAgentResult> completion() { return completion; }
    public AtomicReference<SubAgentStatus> status() { return status; }
    public AtomicReference<ResultDeliveryState> deliveryState() { return deliveryState; }
    public Instant createdAt() { return createdAt; }
    public CancellationToken taskCancellationToken() { return taskCancellationToken; }
    public SubAgentResult storedResult() { return storedResult; }

    public boolean markLifecycleTerminalPublished() {
        return lifecycleTerminalPublished.compareAndSet(false, true);
    }

    // Publish the result before its terminal status, and complete each task exactly once.
    public synchronized boolean start() {
        if (taskCancellationToken != null && taskCancellationToken.isCancelled()) {
            markCancelled();
            return false;
        }
        return status.compareAndSet(SubAgentStatus.QUEUED, SubAgentStatus.RUNNING);
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

    public synchronized boolean requestCancel() {
        if (isTerminal()) return false;
        boolean queued = status.get() == SubAgentStatus.QUEUED;
        status.set(SubAgentStatus.CANCEL_REQUESTED);
        if (queued) markCancelled();
        if (taskCancellationToken != null) taskCancellationToken.cancel();
        return true;
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
        storedResult = result;
        status.set(terminal);
        completion.complete(result);
    }

    public boolean isTerminal() { return isTerminal(status.get()); }
    public boolean isCancelRequested() { return status.get() == SubAgentStatus.CANCEL_REQUESTED; }

    // --- Delivery state transitions (CAS-based) ---

    /**
     * Detach from inline delivery. Called when await_subagents times out.
     * CAS: INLINE_PENDING → DETACHED
     */
    public boolean detach() {
        return deliveryState.compareAndSet(ResultDeliveryState.INLINE_PENDING, ResultDeliveryState.DETACHED);
    }

    /**
     * Consume result inline. Called when await_subagents gets result before timeout.
     * CAS: INLINE_PENDING → INLINE_CONSUMED
     */
    public boolean consumeInline() {
        return completion.isDone() && deliveryState.compareAndSet(ResultDeliveryState.INLINE_PENDING, ResultDeliveryState.INLINE_CONSUMED);
    }

    /**
     * Mark as session-resumed. Called when completion event is submitted to inbox.
     * CAS: DETACHED → SESSION_RESUMED
     */
    public boolean markSessionResumed() {
        return deliveryState.compareAndSet(ResultDeliveryState.DETACHED, ResultDeliveryState.SESSION_RESUMED);
    }

    /**
     * Check if result was detached (will go to session resume).
     */
    public boolean isDetached() {
        return deliveryState.get() == ResultDeliveryState.DETACHED;
    }

    private static boolean isTerminal(SubAgentStatus status) {
        return status == SubAgentStatus.SUCCEEDED
                || status == SubAgentStatus.INCOMPLETE
                || status == SubAgentStatus.FAILED
                || status == SubAgentStatus.CANCELLED
                || status == SubAgentStatus.TIMED_OUT;
    }
}

package com.harness.agent;

import com.harness.core.model.CancellationToken;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Record of a sub-agent task within a run scope.
 * Tracks task metadata, completion future, lifecycle status, cancellation,
 * and result delivery state.
 *
 * Two independent state machines:
 * - SubAgentStatus: QUEUED → RUNNING → SUCCEEDED/FAILED/CANCELLED/TIMED_OUT
 * - ResultDeliveryState: INLINE_PENDING → INLINE_CONSUMED/DETACHED → SESSION_RESUMED
 */
public class SubAgentTaskRecord {

    private final String taskId;
    private final String ownerSessionId;
    private final SubAgentTask task;
    private final CompletableFuture<SubAgentResult> completion;
    private final AtomicReference<SubAgentStatus> status;
    private final AtomicReference<ResultDeliveryState> deliveryState;
    private final Instant createdAt;
    private final CancellationToken taskCancellationToken;

    // Stored result for detached delivery (set by completion callback)
    private volatile SubAgentResult storedResult;

    public SubAgentTaskRecord(String taskId, String ownerRunId, String ownerSessionId, SubAgentTask task, CancellationToken taskCancellationToken) {
        this.taskId = taskId;
        this.ownerSessionId = ownerSessionId;
        this.task = task;
        this.completion = new CompletableFuture<>();
        this.status = new AtomicReference<>(SubAgentStatus.QUEUED);
        this.deliveryState = new AtomicReference<>(ResultDeliveryState.INLINE_PENDING);
        this.createdAt = Instant.now();
        this.taskCancellationToken = taskCancellationToken;
    }

    public String taskId() { return taskId; }
    public String ownerSessionId() { return ownerSessionId; }
    public SubAgentTask task() { return task; }
    public CompletableFuture<SubAgentResult> completion() { return completion; }
    public AtomicReference<SubAgentStatus> status() { return status; }
    public AtomicReference<ResultDeliveryState> deliveryState() { return deliveryState; }
    public Instant createdAt() { return createdAt; }
    public CancellationToken taskCancellationToken() { return taskCancellationToken; }
    public SubAgentResult storedResult() { return storedResult; }

    // --- Execution status transitions ---

    /**
     * Transition to RUNNING state. Returns false if already in a terminal state.
     */
    public boolean start() {
        return status.compareAndSet(SubAgentStatus.QUEUED, SubAgentStatus.RUNNING);
    }

    /**
     * Mark as succeeded with result. Uses CAS to prevent overwriting terminal states.
     */
    public void succeed(SubAgentResult result) {
        SubAgentStatus current;
        do {
            current = status.get();
            if (current == SubAgentStatus.FAILED || current == SubAgentStatus.CANCELLED ||
                current == SubAgentStatus.TIMED_OUT) {
                return; // Already in a terminal state, don't overwrite
            }
        } while (!status.compareAndSet(current, SubAgentStatus.SUCCEEDED));
        this.storedResult = result;
        completion.complete(result);
    }

    /**
     * Mark as failed with error. Uses CAS to prevent overwriting terminal states.
     */
    public void fail(SubAgentResult result) {
        SubAgentStatus current;
        do {
            current = status.get();
            if (current == SubAgentStatus.SUCCEEDED || current == SubAgentStatus.CANCELLED ||
                current == SubAgentStatus.TIMED_OUT) {
                return; // Already in a terminal state, don't overwrite
            }
        } while (!status.compareAndSet(current, SubAgentStatus.FAILED));
        this.storedResult = result;
        completion.complete(result);
    }

    /**
     * Request cancellation. Transitions to CANCEL_REQUESTED.
     * Returns false if already in a terminal state, true if cancellation requested (including already requested).
     */
    public boolean requestCancel() {
        SubAgentStatus current;
        do {
            current = status.get();
            if (current == SubAgentStatus.SUCCEEDED || current == SubAgentStatus.FAILED ||
                current == SubAgentStatus.CANCELLED || current == SubAgentStatus.TIMED_OUT) {
                return false;
            }
            if (current == SubAgentStatus.CANCEL_REQUESTED) {
                return true; // Already requested
            }
        } while (!status.compareAndSet(current, SubAgentStatus.CANCEL_REQUESTED));
        if (taskCancellationToken != null) {
            taskCancellationToken.cancel();
        }
        return true;
    }

    /**
     * Mark as cancelled. Uses CAS to prevent overwriting any terminal state.
     */
    public void markCancelled() {
        SubAgentStatus current;
        do {
            current = status.get();
            if (current == SubAgentStatus.SUCCEEDED || current == SubAgentStatus.FAILED ||
                current == SubAgentStatus.TIMED_OUT) {
                return;
            }
        } while (!status.compareAndSet(current, SubAgentStatus.CANCELLED));
        this.storedResult = SubAgentResult.failure(taskId, "Cancelled", java.util.List.of(), 0);
        completion.complete(storedResult);
    }

    /**
     * Mark as timed out. Uses CAS to prevent overwriting any terminal state.
     */
    public void markTimedOut() {
        SubAgentStatus current;
        do {
            current = status.get();
            if (current == SubAgentStatus.SUCCEEDED || current == SubAgentStatus.FAILED ||
                current == SubAgentStatus.CANCELLED) {
                return;
            }
        } while (!status.compareAndSet(current, SubAgentStatus.TIMED_OUT));
        this.storedResult = SubAgentResult.failure(taskId, "Task timed out", java.util.List.of(), 0);
        completion.complete(storedResult);
    }

    /**
     * Check if task is in a terminal state.
     */
    public boolean isTerminal() {
        SubAgentStatus s = status.get();
        return s == SubAgentStatus.SUCCEEDED || s == SubAgentStatus.FAILED ||
               s == SubAgentStatus.CANCELLED || s == SubAgentStatus.TIMED_OUT;
    }

    /**
     * Check if cancellation was requested.
     */
    public boolean isCancelRequested() {
        return status.get() == SubAgentStatus.CANCEL_REQUESTED;
    }

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
        return deliveryState.compareAndSet(ResultDeliveryState.INLINE_PENDING, ResultDeliveryState.INLINE_CONSUMED);
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

    /**
     * Store result from completion callback (for later delivery).
     */
    public void storeCompletionResult(SubAgentResult result) {
        this.storedResult = result;
    }
}

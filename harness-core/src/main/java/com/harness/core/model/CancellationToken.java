package com.harness.core.model;

/**
 * Thread-safe token for cancelling in-progress agent runs.
 * Shared between the HTTP handler (which signals cancellation) and the ReAct loop (which checks it).
 */
public class CancellationToken {

    private volatile boolean cancelled = false;

    /**
     * Signal cancellation. Safe to call from any thread.
     */
    public void cancel() {
        this.cancelled = true;
    }

    /**
     * Check if cancellation has been requested.
     */
    public boolean isCancelled() {
        return cancelled;
    }
}

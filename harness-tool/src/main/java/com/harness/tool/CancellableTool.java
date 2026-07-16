package com.harness.tool;

/**
 * Marker interface for tools that support cancellation of in-progress HTTP requests.
 * Tools implementing this interface can have their active requests cancelled
 * when the user cancels a chat request.
 *
 * <p>The {@link #cancel()} method should be idempotent and thread-safe.</p>
 */
public interface CancellableTool extends Tool {

    /**
     * Cancel all active requests initiated by this tool.
     * Called when the user cancels a chat request.
     * Should be idempotent and thread-safe.
     */
    void cancel();
}

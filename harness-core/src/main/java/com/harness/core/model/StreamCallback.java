package com.harness.core.model;

/**
 * Callback interface for receiving streaming events from the agent.
 * Implementations write to SSE stream or print to CLI stdout.
 */
@FunctionalInterface
public interface StreamCallback {
    void onEvent(StreamEvent event);
}

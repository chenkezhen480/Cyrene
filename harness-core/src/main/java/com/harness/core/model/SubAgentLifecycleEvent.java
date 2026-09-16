package com.harness.core.model;

import java.util.Objects;

/** Observable lifecycle of one sub-agent nested under its spawn Tool call. */
public record SubAgentLifecycleEvent(
        String toolCallId,
        String taskId,
        Status status,
        String detail
) {
    public enum Status {
        PREPARING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED,
        TIMED_OUT
    }

    public SubAgentLifecycleEvent {
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId must not be blank");
        }
        Objects.requireNonNull(status, "status");
        detail = detail != null ? detail : "";
    }
}

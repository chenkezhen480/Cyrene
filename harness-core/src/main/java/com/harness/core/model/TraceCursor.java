package com.harness.core.model;

import java.time.Instant;
import java.util.Objects;

public record TraceCursor(Instant timestamp, String traceId) {
    public TraceCursor {
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
        if (traceId == null || traceId.isBlank() || traceId.length() > 64) {
            throw new IllegalArgumentException("traceId is required and must not exceed 64 characters");
        }
        traceId = traceId.trim();
    }
}

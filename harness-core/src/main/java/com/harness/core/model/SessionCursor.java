package com.harness.core.model;

import java.time.Instant;

public record SessionCursor(Instant lastActive, String sessionId) {
    public SessionCursor {
        if (lastActive == null) {
            throw new IllegalArgumentException("lastActive is required");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
    }
}

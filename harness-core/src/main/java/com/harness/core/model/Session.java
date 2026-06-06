package com.harness.core.model;

import java.time.Instant;

/**
 * Represents a user conversation session.
 */
public record Session(
        String id,
        String userId,
        String title,
        Instant createdAt,
        Instant lastActive,
        Instant endedAt,
        SessionStatus status
) {
    public enum SessionStatus {
        active, ended, timeout
    }
}

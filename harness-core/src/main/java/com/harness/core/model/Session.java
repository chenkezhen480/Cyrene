package com.harness.core.model;

import java.time.Instant;

/**
 * Represents a user conversation session.
 */
public record Session(
        String id,
        String userId,
        String tenantId,
        String title,
        Instant createdAt,
        Instant lastActive,
        Instant endedAt,
        SessionStatus status,
        String identity
) {
    public Session(String id, String userId, String tenantId, String title, Instant createdAt,
                   Instant lastActive, Instant endedAt, SessionStatus status) {
        this(id, userId, tenantId, title, createdAt, lastActive, endedAt, status, null);
    }

    public enum SessionStatus {
        active, ended, timeout
    }
}

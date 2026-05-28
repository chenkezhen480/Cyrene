package com.harness.core.model;

import java.time.Instant;

/**
 * A long-term user preference extracted from conversation sessions.
 */
public record Preference(
        long id,
        String userId,
        String category,
        String content,
        String sourceSessionId,
        Instant createdAt,
        Instant updatedAt
) {}

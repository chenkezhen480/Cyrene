package com.harness.core.model;

import java.time.Instant;

/**
 * A persisted conversation message within a session.
 * Named MemoryMessage to avoid collision with AgentMessage.
 */
public record MemoryMessage(
        long id,
        String sessionId,
        String role,
        String content,
        boolean isSummary,
        Instant createdAt
) {}

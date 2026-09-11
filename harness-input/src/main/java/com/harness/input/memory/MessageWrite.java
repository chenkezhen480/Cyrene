package com.harness.input.memory;

import com.harness.core.model.MessageBlock;

import java.util.List;

public record MessageWrite(
        String sessionId,
        String traceId,
        String role,
        List<MessageBlock> content,
        boolean isSummary
) {
    public MessageWrite {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        traceId = traceId == null || traceId.isBlank() ? null : traceId.trim();
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role is required");
        }
        content = List.copyOf(content == null ? List.of() : content);
    }
}

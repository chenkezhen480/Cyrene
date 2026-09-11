package com.harness.core.knowledge;

import java.time.Instant;
import java.util.Objects;

public record KnowledgeIndexTask(
        Long id,
        String conceptId,
        String revisionId,
        KnowledgeIndexOperation operation,
        KnowledgeIndexTaskStatus status,
        int attempts,
        Instant availableAt,
        Instant claimedAt,
        Instant completedAt,
        String errorMessage,
        Instant createdAt
) {
    public KnowledgeIndexTask {
        if (id != null && id < 1) {
            throw new IllegalArgumentException("id must be positive when present");
        }
        conceptId = KnowledgeModelSupport.requiredText(conceptId, "conceptId", 64);
        revisionId = KnowledgeModelSupport.requiredText(revisionId, "revisionId", 64);
        operation = Objects.requireNonNull(operation, "operation");
        status = Objects.requireNonNull(status, "status");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
        availableAt = Objects.requireNonNull(availableAt, "availableAt");
        errorMessage = KnowledgeModelSupport.optionalText(errorMessage, "errorMessage", 1024);
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }
}

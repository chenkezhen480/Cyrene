package com.harness.core.knowledge;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record KnowledgeRevision(
        String id,
        String conceptId,
        long revisionNumber,
        String title,
        String description,
        String body,
        String generatedBy,
        Instant generatedAt,
        String contentHash,
        Map<String, Object> metadata,
        Instant createdAt
) {
    public KnowledgeRevision {
        id = KnowledgeModelSupport.requiredText(id, "id", 64);
        conceptId = KnowledgeModelSupport.requiredText(conceptId, "conceptId", 64);
        if (revisionNumber < 1) {
            throw new IllegalArgumentException("revisionNumber must be positive");
        }
        title = KnowledgeModelSupport.requiredText(title, "title", 512);
        description = KnowledgeModelSupport.optionalText(description, "description", 2048);
        body = Objects.requireNonNull(body, "body");
        generatedBy = KnowledgeModelSupport.requiredText(generatedBy, "generatedBy", 256);
        generatedAt = Objects.requireNonNull(generatedAt, "generatedAt");
        contentHash = KnowledgeModelSupport.requiredText(contentHash, "contentHash", 64);
        metadata = KnowledgeModelSupport.immutableMap(metadata);
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }
}

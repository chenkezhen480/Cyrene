package com.harness.core.knowledge;

import java.time.Instant;
import java.util.Objects;

public record KnowledgeSource(
        String revisionId,
        KnowledgeSourceType sourceType,
        String sourceId,
        String sourceResource,
        Instant observedAt,
        Instant createdAt
) {
    public KnowledgeSource {
        revisionId = KnowledgeModelSupport.requiredText(revisionId, "revisionId", 64);
        sourceType = Objects.requireNonNull(sourceType, "sourceType");
        sourceId = KnowledgeModelSupport.requiredText(sourceId, "sourceId", 128);
        sourceResource = KnowledgeModelSupport.requiredText(sourceResource, "sourceResource", 1024);
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (!sourceResource.startsWith("cyrene://")) {
            throw new IllegalArgumentException("sourceResource must use the cyrene scheme");
        }
    }
}

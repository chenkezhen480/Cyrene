package com.harness.core.knowledge;

import java.time.Instant;
import java.util.Objects;

public record KnowledgeIngestJob(
        String id,
        String artifactId,
        String tenantId,
        String collectionKey,
        Status status,
        int attempts,
        Instant availableAt,
        Instant claimedAt,
        String convertedArtifactId,
        String sourceConceptId,
        String sourceRevisionId,
        String errorMessage,
        Instant createdAt,
        Instant completedAt
) {
    public KnowledgeIngestJob {
        id = KnowledgeModelSupport.requiredText(id, "id", 64);
        artifactId = KnowledgeModelSupport.requiredText(artifactId, "artifactId", 64);
        tenantId = KnowledgeModelSupport.optionalText(tenantId, "tenantId", 128);
        collectionKey = KnowledgeModelSupport.requiredText(collectionKey, "collectionKey", 128);
        status = Objects.requireNonNull(status, "status");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
        availableAt = Objects.requireNonNull(availableAt, "availableAt");
        convertedArtifactId = KnowledgeModelSupport.optionalText(
                convertedArtifactId, "convertedArtifactId", 64);
        sourceConceptId = KnowledgeModelSupport.optionalText(sourceConceptId, "sourceConceptId", 64);
        sourceRevisionId = KnowledgeModelSupport.optionalText(sourceRevisionId, "sourceRevisionId", 64);
        errorMessage = KnowledgeModelSupport.optionalText(errorMessage, "errorMessage", 1024);
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public enum Status {
        UPLOADED,
        CONVERTED,
        COMPILED,
        INDEXED,
        FAILED
    }
}

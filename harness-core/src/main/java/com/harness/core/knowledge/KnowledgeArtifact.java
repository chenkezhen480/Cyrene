package com.harness.core.knowledge;

import java.time.Instant;
import java.util.Objects;

public record KnowledgeArtifact(
        String id,
        String tenantId,
        String collectionKey,
        KnowledgeArtifactType artifactType,
        String fileName,
        String mediaType,
        String contentHash,
        String storageUri,
        Status status,
        Instant createdAt
) {
    public KnowledgeArtifact {
        id = KnowledgeModelSupport.requiredText(id, "id", 64);
        tenantId = KnowledgeModelSupport.optionalText(tenantId, "tenantId", 128);
        collectionKey = KnowledgeModelSupport.requiredText(collectionKey, "collectionKey", 128);
        artifactType = Objects.requireNonNull(artifactType, "artifactType");
        fileName = KnowledgeModelSupport.requiredText(fileName, "fileName", 512);
        mediaType = KnowledgeModelSupport.requiredText(mediaType, "mediaType", 255);
        contentHash = KnowledgeModelSupport.requiredText(contentHash, "contentHash", 64);
        storageUri = KnowledgeModelSupport.requiredText(storageUri, "storageUri", 2048);
        status = Objects.requireNonNull(status, "status");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public enum Status {
        ACTIVE,
        DEPRECATED
    }
}

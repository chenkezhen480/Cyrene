package com.harness.tool.knowledge.authority;

import com.harness.core.knowledge.ArtifactCursor;
import com.harness.core.knowledge.KnowledgeArtifact;
import com.harness.core.knowledge.KnowledgeIngestJob;
import com.harness.core.model.PageResponse;

import java.util.Optional;

public interface KnowledgeArtifactRepository {

    Registration registerWithIngestJob(KnowledgeArtifact artifact, KnowledgeIngestJob ingestJob);

    KnowledgeArtifact register(KnowledgeArtifact artifact);

    Optional<KnowledgeArtifact> findById(String artifactId);

    Optional<KnowledgeArtifact> findByContent(
            String tenantId,
            String collectionKey,
            String contentHash,
            com.harness.core.knowledge.KnowledgeArtifactType artifactType);

    PageResponse<KnowledgeArtifact> findPage(
            String tenantId,
            String collectionKey,
            String fileNamePrefix,
            ArtifactCursor cursor,
            int limit);

    boolean storageUriExists(String storageUri);

    record Registration(KnowledgeArtifact artifact, KnowledgeIngestJob ingestJob) {
        public Registration {
            if (artifact == null || ingestJob == null) {
                throw new IllegalArgumentException("artifact and ingestJob are required");
            }
        }
    }
}

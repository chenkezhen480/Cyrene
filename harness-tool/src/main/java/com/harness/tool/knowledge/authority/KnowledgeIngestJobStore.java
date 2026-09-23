package com.harness.tool.knowledge.authority;

import com.harness.core.knowledge.KnowledgeIngestJob;
import com.harness.core.model.PageResponse;

import java.time.Instant;
import java.util.Optional;

public interface KnowledgeIngestJobStore {

    Optional<KnowledgeIngestJob> findById(String jobId);

    Optional<KnowledgeIngestJob> claim(String jobId, Instant now);

    KnowledgeIngestJob advance(
            String jobId,
            KnowledgeIngestJob.Status expectedStatus,
            KnowledgeIngestJob.Status nextStatus,
            String convertedArtifactId,
            String sourceConceptId,
            String sourceRevisionId,
            Instant completedAt);

    default KnowledgeIngestJob commitCompilation(
            String jobId, KnowledgeRevisionChange change) {
        return commitCompilation(jobId, change.concept().id(), change);
    }

    KnowledgeIngestJob commitCompilation(
            String jobId, String expectedSourceConceptId, KnowledgeRevisionChange change);

    void markFailed(String jobId, Instant completedAt, String errorMessage);

    PageResponse<KnowledgeIngestJob> findPage(String afterJobId, int limit);
}

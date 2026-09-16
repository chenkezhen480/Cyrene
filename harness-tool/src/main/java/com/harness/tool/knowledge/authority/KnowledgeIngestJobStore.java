package com.harness.tool.knowledge.authority;

import com.harness.core.knowledge.KnowledgeIngestJob;
import com.harness.core.model.PageResponse;

import java.time.Instant;
import java.util.Optional;

public interface KnowledgeIngestJobStore {

    Optional<KnowledgeIngestJob> findById(String jobId);

    Optional<KnowledgeIngestJob> claimNext(Instant now);

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

    void reschedule(String jobId, Instant availableAt, String errorMessage);

    void markFailed(String jobId, Instant completedAt, String errorMessage);

    void replayFailed(String jobId, Instant availableAt);

    int recoverStuck(Instant claimedBefore, Instant availableAt);

    PageResponse<KnowledgeIngestJob> findPage(String afterJobId, int limit);
}

package com.harness.tool.knowledge.authority;

import com.harness.core.knowledge.KnowledgeIndexTask;
import com.harness.core.model.PageResponse;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface KnowledgeIndexOutboxStore {

    Optional<KnowledgeIndexTask> findById(long taskId);

    Optional<KnowledgeIndexTask> claimNext(Instant now);

    void markCompleted(long taskId, Instant completedAt);

    void reschedule(long taskId, Instant availableAt, String errorMessage);

    void markFailed(long taskId, Instant completedAt, String errorMessage);

    void replayFailed(long taskId, Instant availableAt);

    int recoverStuck(Instant claimedBefore, Instant availableAt);

    PageResponse<KnowledgeIndexTask> findPage(long afterId, int limit);

    PageResponse<KnowledgeIndexTask> findPageByConceptIds(
            List<String> conceptIds,
            long afterId,
            int limit);
}

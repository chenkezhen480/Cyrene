package com.harness.tool.knowledge.authority;

import com.harness.graph.model.GraphChangeSet;
import com.harness.graph.model.GraphMutationResult;

import java.time.Instant;
import java.util.Optional;

public interface KnowledgeGraphMutationJobStore {
    KnowledgeGraphMutationJob register(GraphChangeSet changeSet, String tenantId,
                                       String sourceRevisionId, Instant now);

    Optional<KnowledgeGraphMutationJob> findById(String requestId);

    Optional<KnowledgeGraphMutationJob> claim(String requestId, Instant now);

    Optional<KnowledgeGraphMutationJob> claimNext(Instant now);

    KnowledgeGraphMutationJob markGraphCommitted(String requestId,
                                                  GraphMutationResult result, Instant now);

    KnowledgeGraphMutationJob completeKnowledge(String requestId,
                                                String revisionId, Instant now);

    void reschedule(String requestId, Instant availableAt, String errorMessage);

    void markFailed(String requestId, Instant completedAt, String errorMessage);

    int recoverStuck(Instant claimedBefore, Instant availableAt);
}

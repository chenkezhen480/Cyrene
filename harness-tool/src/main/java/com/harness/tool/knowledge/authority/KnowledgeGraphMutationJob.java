package com.harness.tool.knowledge.authority;

import com.harness.graph.model.GraphChangeSet;

import java.time.Instant;

public record KnowledgeGraphMutationJob(
        GraphChangeSet changeSet,
        String tenantId,
        String sourceRevisionId,
        String payloadHash,
        String canonicalPayload,
        Status status,
        int attempts,
        Instant availableAt,
        Instant claimedAt,
        Integer graphNodeCount,
        Integer graphRelationCount,
        Instant graphCommittedAt,
        Instant completedAt,
        String errorMessage,
        Instant createdAt
) {
    public enum Status {
        PENDING,
        GRAPH_COMMITTED,
        KNOWLEDGE_COMMITTED,
        FAILED
    }
}

package com.harness.tool.knowledge.index;

import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeNamespaceType;

import java.util.Set;

/** Bounded, scope-aware hybrid query over one dedicated projection collection. */
public record KnowledgeProjectionSearch(
        String query,
        float[] embedding,
        String tenantId,
        String userId,
        KnowledgeNamespaceType namespaceType,
        String namespaceKey,
        boolean exactTenant,
        Set<KnowledgeConceptType> conceptTypes,
        int laneTopK,
        int fusedTopK,
        double denseThreshold,
        double sparseThreshold,
        int rrfK,
        double bm25Weight
) {
    public KnowledgeProjectionSearch(
            String query,
            float[] embedding,
            String tenantId,
            String userId,
            Set<KnowledgeConceptType> conceptTypes,
            int laneTopK,
            int fusedTopK,
            double denseThreshold,
            double sparseThreshold,
            int rrfK
    ) {
        this(query, embedding, tenantId, userId, null, null, false, conceptTypes,
                laneTopK, fusedTopK, denseThreshold, sparseThreshold, rrfK, 0.5);
    }

    public KnowledgeProjectionSearch(String query, float[] embedding, String tenantId, String userId,
            KnowledgeNamespaceType namespaceType, String namespaceKey, boolean exactTenant,
            Set<KnowledgeConceptType> conceptTypes, int laneTopK, int fusedTopK,
            double denseThreshold, double sparseThreshold, int rrfK) {
        this(query, embedding, tenantId, userId, namespaceType, namespaceKey, exactTenant, conceptTypes,
                laneTopK, fusedTopK, denseThreshold, sparseThreshold, rrfK, 0.5);
    }

    public KnowledgeProjectionSearch {
        if (query == null || query.isBlank() || query.length() > 4096) {
            throw new IllegalArgumentException("query must contain 1 to 4096 characters");
        }
        query = query.trim();
        if (embedding == null || (embedding.length == 0 && bm25Weight < 1)) {
            throw new IllegalArgumentException("query embedding is required");
        }
        embedding = embedding.clone();
        tenantId = optional(tenantId);
        userId = optional(userId);
        namespaceKey = optional(namespaceKey);
        if (namespaceKey != null && namespaceType == null) {
            throw new IllegalArgumentException("namespaceType is required with namespaceKey");
        }
        conceptTypes = Set.copyOf(conceptTypes == null ? Set.of() : conceptTypes);
        if (laneTopK < 1 || laneTopK > 100 || fusedTopK < 1 || fusedTopK > laneTopK) {
            throw new IllegalArgumentException(
                    "laneTopK must be 1..100 and fusedTopK must not exceed laneTopK");
        }
        if (!Double.isFinite(denseThreshold) || !Double.isFinite(sparseThreshold)
                || denseThreshold < -1 || denseThreshold > 1 || sparseThreshold < 0) {
            throw new IllegalArgumentException("invalid hybrid search thresholds");
        }
        if (!Double.isFinite(bm25Weight) || bm25Weight < 0 || bm25Weight > 1) {
            throw new IllegalArgumentException("bm25Weight must be between 0 and 1");
        }
        if (rrfK < 1 || rrfK > 1000) {
            throw new IllegalArgumentException("rrfK must be between 1 and 1000");
        }
    }

    @Override
    public float[] embedding() {
        return embedding.clone();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

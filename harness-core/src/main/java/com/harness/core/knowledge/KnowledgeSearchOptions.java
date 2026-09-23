package com.harness.core.knowledge;

/** Per-call retrieval policy; trusted scope is deliberately not part of this object. */
public record KnowledgeSearchOptions(
        int limit,
        int candidateTopK,
        double bm25Weight,
        double denseThreshold,
        double sparseThreshold,
        boolean rerank
) {
    public static final int MAX_LIMIT = 20;
    public static final int MAX_CANDIDATES = 100;
    public static final int RRF_K = 60;

    public KnowledgeSearchOptions {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        if (candidateTopK < limit || candidateTopK > MAX_CANDIDATES) {
            throw new IllegalArgumentException("candidateTopK must be between limit and " + MAX_CANDIDATES);
        }
        if (!Double.isFinite(bm25Weight) || bm25Weight < 0 || bm25Weight > 1) {
            throw new IllegalArgumentException("bm25Weight must be between 0 and 1");
        }
        if (!Double.isFinite(denseThreshold) || denseThreshold < -1 || denseThreshold > 1) {
            throw new IllegalArgumentException("denseThreshold must be between -1 and 1 (COSINE)");
        }
        if (!Double.isFinite(sparseThreshold) || sparseThreshold < 0) {
            throw new IllegalArgumentException("sparseThreshold must be a finite nonnegative BM25 score");
        }
    }

    public static KnowledgeSearchOptions defaults(int limit) {
        return configured(limit, 20, 0.5, true);
    }

    public static KnowledgeSearchOptions configured(int limit, int candidates, double weight, boolean rerank) {
        var config = com.harness.core.env.EnvConfig.get();
        return new KnowledgeSearchOptions(limit, candidates, weight,
                config.getDouble(com.harness.core.env.EnvKey.RAG_SCORE_THRESHOLD, 0.5),
                config.getDouble(com.harness.core.env.EnvKey.KNOWLEDGE_CATALOG_RETRIEVAL_SPARSE_THRESHOLD, 0.1), rerank);
    }
}

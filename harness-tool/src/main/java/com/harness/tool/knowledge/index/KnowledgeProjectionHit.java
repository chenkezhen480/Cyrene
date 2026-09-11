package com.harness.tool.knowledge.index;

/** One RRF-fused hit; score is meaningful only within its projection kind. */
public record KnowledgeProjectionHit(
        KnowledgeProjection projection,
        double rrfScore
) {
    public KnowledgeProjectionHit {
        projection = java.util.Objects.requireNonNull(projection, "projection");
        if (!Double.isFinite(rrfScore) || rrfScore < 0) {
            throw new IllegalArgumentException("rrfScore must be finite and non-negative");
        }
    }
}

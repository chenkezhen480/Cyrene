package com.harness.tool.knowledge.index;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One-based deterministic RRF shared by provider implementations and tests. */
public final class ReciprocalRankFusion {

    private ReciprocalRankFusion() {
    }

    public static List<KnowledgeProjectionHit> fuse(
            List<KnowledgeProjection> dense,
            List<KnowledgeProjection> sparse,
            int rrfK,
            int limit
    ) {
        if (rrfK < 1 || limit < 1) {
            throw new IllegalArgumentException("rrfK and limit must be positive");
        }
        Map<String, MutableHit> hits = new LinkedHashMap<>();
        addLane(hits, dense, rrfK);
        addLane(hits, sparse, rrfK);
        return hits.values().stream()
                .map(hit -> new KnowledgeProjectionHit(hit.projection, hit.score))
                .sorted(Comparator.comparingDouble(KnowledgeProjectionHit::rrfScore)
                        .reversed()
                        .thenComparing(hit -> hit.projection().revisionId()))
                .limit(limit)
                .toList();
    }

    private static void addLane(
            Map<String, MutableHit> hits,
            List<KnowledgeProjection> lane,
            int rrfK
    ) {
        List<KnowledgeProjection> safeLane = lane == null ? List.of() : lane;
        for (int index = 0; index < safeLane.size(); index++) {
            KnowledgeProjection projection = java.util.Objects.requireNonNull(
                    safeLane.get(index), "projection lane item");
            MutableHit hit = hits.computeIfAbsent(
                    projection.revisionId(), ignored -> new MutableHit(projection));
            hit.score += 1.0 / (rrfK + index + 1.0);
        }
    }

    private static final class MutableHit {
        private final KnowledgeProjection projection;
        private double score;

        private MutableHit(KnowledgeProjection projection) {
            this.projection = projection;
        }
    }
}

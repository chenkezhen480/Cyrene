package com.harness.tool.knowledge.index;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** One-based weighted RRF shared by Wiki projections and document chunks. */
public final class ReciprocalRankFusion {
    private ReciprocalRankFusion() {}

    public static List<KnowledgeProjectionHit> fuse(List<KnowledgeProjection> dense,
            List<KnowledgeProjection> sparse, int rrfK, int limit) {
        return rank(dense, sparse, KnowledgeProjection::revisionId, 1, 1, rrfK, limit).stream()
                .map(hit -> new KnowledgeProjectionHit(hit.item(), hit.score())).toList();
    }

    public static <T> List<Ranked<T>> rank(List<T> dense, List<T> sparse, Function<T, String> identity,
            double denseWeight, double sparseWeight, int rrfK, int limit) {
        if (rrfK < 1 || limit < 1 || !Double.isFinite(denseWeight) || !Double.isFinite(sparseWeight)
                || denseWeight < 0 || sparseWeight < 0 || denseWeight + sparseWeight <= 0) {
            throw new IllegalArgumentException("RRF requires positive limits and finite nonnegative weights");
        }
        Map<String, Ranked<T>> hits = new LinkedHashMap<>();
        addLane(hits, dense, identity, denseWeight, rrfK);
        addLane(hits, sparse, identity, sparseWeight, rrfK);
        return hits.values().stream()
                .sorted(Comparator.<Ranked<T>>comparingDouble(Ranked::score).reversed()
                        .thenComparing(hit -> identity.apply(hit.item())))
                .limit(limit).toList();
    }

    private static <T> void addLane(Map<String, Ranked<T>> hits, List<T> lane,
            Function<T, String> identity, double weight, int rrfK) {
        if (weight == 0) return;
        for (int index = 0; index < lane.size(); index++) {
            T item = lane.get(index);
            double score = weight / (rrfK + index + 1.0);
            hits.merge(identity.apply(item), new Ranked<>(item, score),
                    (previous, next) -> new Ranked<>(previous.item(), previous.score() + next.score()));
        }
    }

    public record Ranked<T>(T item, double score) {}
}

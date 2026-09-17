package com.harness.tool.knowledge.index;

import java.util.List;

/**
 * Lane-level evidence for one hybrid Wiki search.
 *
 * <p>The dense and sparse thresholds are applied client-side precisely so these numbers exist:
 * a search that returns nothing can then be told apart from a search whose candidates were all
 * cut by the threshold. Counts are aggregated over the searched projection collections, which
 * {@link #collections()} names.</p>
 *
 * @param denseCandidates  dense neighbours returned before the dense threshold is applied
 * @param denseBestScore   best observed dense score, or null when the lane returned nothing
 * @param denseKept        dense neighbours that survived the threshold
 * @param sparseCandidates sparse neighbours returned before the sparse threshold
 * @param sparseBestScore  best observed sparse score, or null when the lane returned nothing
 * @param sparseKept       sparse neighbours that survived the threshold
 * @param fusedCandidates  unique revisions surviving fusion, before the final limit
 */
public record KnowledgeRetrievalDiagnostics(
        List<String> collections,
        int denseCandidates,
        Double denseBestScore,
        double denseThreshold,
        int denseKept,
        int sparseCandidates,
        Double sparseBestScore,
        double sparseThreshold,
        int sparseKept,
        int fusedCandidates
) {
    public KnowledgeRetrievalDiagnostics {
        collections = List.copyOf(collections == null ? List.of() : collections);
    }

    public static KnowledgeRetrievalDiagnostics empty(
            double denseThreshold, double sparseThreshold) {
        return new KnowledgeRetrievalDiagnostics(
                List.of(), 0, null, denseThreshold, 0, 0, null, sparseThreshold, 0, 0);
    }

    /**
     * Combine the lanes of every collection searched for one query. Best scores are maxima and
     * counts are sums, so a threshold verdict can be read straight off the aggregate.
     */
    static KnowledgeRetrievalDiagnostics merge(
            List<KnowledgeRetrievalDiagnostics> parts,
            double denseThreshold,
            double sparseThreshold
    ) {
        java.util.List<String> collections = new java.util.ArrayList<>();
        int denseCandidates = 0;
        int denseKept = 0;
        int sparseCandidates = 0;
        int sparseKept = 0;
        int fused = 0;
        Double denseBest = null;
        Double sparseBest = null;
        for (KnowledgeRetrievalDiagnostics part : parts) {
            collections.addAll(part.collections());
            denseCandidates += part.denseCandidates();
            denseKept += part.denseKept();
            sparseCandidates += part.sparseCandidates();
            sparseKept += part.sparseKept();
            fused += part.fusedCandidates();
            if (part.denseBestScore() != null
                    && (denseBest == null || part.denseBestScore() > denseBest)) {
                denseBest = part.denseBestScore();
            }
            if (part.sparseBestScore() != null
                    && (sparseBest == null || part.sparseBestScore() > sparseBest)) {
                sparseBest = part.sparseBestScore();
            }
        }
        return new KnowledgeRetrievalDiagnostics(
                collections, denseCandidates, denseBest, denseThreshold, denseKept,
                sparseCandidates, sparseBest, sparseThreshold, sparseKept, fused);
    }
}

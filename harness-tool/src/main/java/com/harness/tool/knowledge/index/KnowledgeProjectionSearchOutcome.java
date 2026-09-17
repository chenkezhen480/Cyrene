package com.harness.tool.knowledge.index;

import java.util.List;

/**
 * Fused hits plus the lane evidence they were selected from.
 *
 * <p>Scores are fused ranks, so the scores next to {@link KnowledgeRetrievalDiagnostics} are not
 * comparable with them; the diagnostics exist to explain how many candidates each lane
 * contributed and which threshold removed them.</p>
 */
public record KnowledgeProjectionSearchOutcome(
        List<KnowledgeProjectionHit> hits,
        KnowledgeRetrievalDiagnostics diagnostics
) {
    public KnowledgeProjectionSearchOutcome {
        hits = List.copyOf(hits == null ? List.of() : hits);
    }
}

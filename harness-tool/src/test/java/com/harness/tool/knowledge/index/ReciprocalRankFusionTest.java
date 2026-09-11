package com.harness.tool.knowledge.index;

import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeNamespaceType;
import com.harness.core.knowledge.KnowledgeRouteTarget;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReciprocalRankFusionTest {

    @Test
    void accumulatesBothOneBasedLanesAndKeepsStableOrder() {
        KnowledgeProjection alpha = projection("rev-alpha");
        KnowledgeProjection beta = projection("rev-beta");
        KnowledgeProjection gamma = projection("rev-gamma");

        List<KnowledgeProjectionHit> hits = ReciprocalRankFusion.fuse(
                List.of(alpha, beta),
                List.of(beta, gamma),
                60,
                20);

        assertThat(hits).extracting(hit -> hit.projection().revisionId())
                .containsExactly("rev-beta", "rev-alpha", "rev-gamma");
        assertThat(hits.getFirst().rrfScore())
                .isEqualTo(1.0 / 62.0 + 1.0 / 61.0);
        assertThat(hits.get(1).rrfScore()).isEqualTo(1.0 / 61.0);
        assertThat(hits.get(2).rrfScore()).isEqualTo(1.0 / 62.0);
    }

    @Test
    void appliesFusedLimitAfterRanking() {
        assertThat(ReciprocalRankFusion.fuse(
                List.of(projection("rev-b"), projection("rev-c")),
                List.of(projection("rev-a")),
                60,
                2))
                .extracting(hit -> hit.projection().revisionId())
                .containsExactly("rev-a", "rev-b");
    }

    private static KnowledgeProjection projection(String revisionId) {
        return new KnowledgeProjection(
                revisionId,
                "concept-" + revisionId,
                revisionId,
                "tenant-a",
                null,
                KnowledgeNamespaceType.COLLECTION,
                "collection-a",
                KnowledgeConceptType.SOURCE_DOCUMENT,
                KnowledgeRouteTarget.DOCUMENT,
                "cyrene://knowledge/concept-" + revisionId,
                revisionId,
                null,
                "content",
                Instant.parse("2026-01-01T00:00:00Z"),
                null,
                null,
                null,
                List.of(),
                null);
    }
}

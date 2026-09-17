package com.harness.agent.knowledge;

import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeHandle;
import com.harness.core.knowledge.KnowledgeRouteTarget;

import java.util.List;
import java.util.Map;

/** One authorized discovery result with its native score semantics preserved. */
public record DiscoveredKnowledge(
        KnowledgeConceptType knowledgeKind,
        String conceptId,
        String currentRevisionId,
        KnowledgeRouteTarget routeTarget,
        KnowledgeHandle handle,
        String title,
        String summary,
        String scoreType,
        double score,
        List<Map<String, Object>> sourceAnchors,
        Map<String, Object> graphRouteHint,
        // When the recorded event happened; only User Episode hits carry one.
        java.time.Instant eventTime
) {
    public DiscoveredKnowledge {
        sourceAnchors = sourceAnchors == null ? List.of() : List.copyOf(sourceAnchors);
        graphRouteHint = graphRouteHint == null ? Map.of() : Map.copyOf(graphRouteHint);
    }
}

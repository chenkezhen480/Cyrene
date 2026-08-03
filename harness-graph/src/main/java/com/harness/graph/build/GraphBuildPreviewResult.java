package com.harness.graph.build;

import com.harness.graph.model.GraphNode;
import com.harness.graph.model.GraphRelation;

import java.util.List;

public record GraphBuildPreviewResult(
        String requestId,
        String graphId,
        String schemaId,
        GraphBuildSourceType sourceType,
        String converterId,
        List<GraphNode> nodes,
        List<GraphRelation> relations
) {
    public GraphBuildPreviewResult {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        relations = relations == null ? List.of() : List.copyOf(relations);
    }
}

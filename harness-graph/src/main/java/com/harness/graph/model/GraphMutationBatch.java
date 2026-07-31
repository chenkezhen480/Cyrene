package com.harness.graph.model;

import java.util.List;

public record GraphMutationBatch(
        String requestId,
        String graphId,
        String schemaId,
        List<GraphNode> nodes,
        List<GraphRelation> relations
) {
    public GraphMutationBatch {
        requestId = GraphModelSupport.requireText(requestId, "requestId");
        graphId = GraphModelSupport.requireText(graphId, "graphId");
        schemaId = GraphModelSupport.requireText(schemaId, "schemaId");
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        relations = relations == null ? List.of() : List.copyOf(relations);
        if (nodes.isEmpty() && relations.isEmpty()) {
            throw new IllegalArgumentException("mutation batch must contain nodes or relations");
        }
    }
}

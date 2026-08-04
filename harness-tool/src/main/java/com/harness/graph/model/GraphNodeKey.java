package com.harness.graph.model;

public record GraphNodeKey(
        String graphId,
        String schemaId,
        String nodeId
) {
    public GraphNodeKey {
        graphId = GraphModelSupport.requireText(graphId, "graphId");
        schemaId = GraphModelSupport.requireText(schemaId, "schemaId");
        nodeId = GraphModelSupport.requireText(nodeId, "nodeId");
    }
}

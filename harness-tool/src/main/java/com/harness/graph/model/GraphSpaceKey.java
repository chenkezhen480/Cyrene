package com.harness.graph.model;

public record GraphSpaceKey(
        String graphId,
        String schemaId
) {
    public GraphSpaceKey {
        graphId = GraphModelSupport.requireText(graphId, "graphId");
        schemaId = GraphModelSupport.requireText(schemaId, "schemaId");
    }
}

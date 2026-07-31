package com.harness.graph.model;

public record GraphDeleteRequest(
        String graphId,
        String schemaId,
        GraphDeleteTarget target,
        String targetId,
        GraphDeleteMode mode
) {
    public GraphDeleteRequest {
        graphId = GraphModelSupport.requireText(graphId, "graphId");
        schemaId = GraphModelSupport.requireText(schemaId, "schemaId");
        if (target == null) {
            throw new IllegalArgumentException("target is required");
        }
        targetId = GraphModelSupport.requireText(targetId, "targetId");
        if (mode == null) {
            throw new IllegalArgumentException("mode is required");
        }
    }
}

package com.harness.graph.model;

public record GraphNodePageRequest(
        String graphId,
        String schemaId,
        String label,
        String name,
        int limit,
        String cursor
) {
    public GraphNodePageRequest {
        graphId = GraphModelSupport.requireText(graphId, "graphId");
        schemaId = GraphModelSupport.requireText(schemaId, "schemaId");
        label = label == null ? "" : label;
        name = name == null ? "" : name.trim();
        limit = GraphModelSupport.requirePositive(limit, "limit");
        cursor = cursor == null ? "" : cursor;
    }
}

package com.harness.graph.model;

public record GraphRelationPageRequest(
        String graphId,
        String schemaId,
        String relationType,
        int limit,
        String cursor
) {
    public GraphRelationPageRequest {
        graphId = GraphModelSupport.requireText(graphId, "graphId");
        schemaId = GraphModelSupport.requireText(schemaId, "schemaId");
        relationType = relationType == null ? "" : relationType;
        limit = GraphModelSupport.requirePositive(limit, "limit");
        cursor = cursor == null ? "" : cursor;
    }
}

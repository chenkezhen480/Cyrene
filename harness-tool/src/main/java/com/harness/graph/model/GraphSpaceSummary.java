package com.harness.graph.model;

public record GraphSpaceSummary(
        String graphId,
        String schemaId,
        long nodeCount,
        long relationCount
) {
    public GraphSpaceSummary {
        graphId = GraphModelSupport.requireText(graphId, "graphId");
        schemaId = GraphModelSupport.requireText(schemaId, "schemaId");
        if (nodeCount < 0) {
            throw new IllegalArgumentException("nodeCount must not be negative");
        }
        if (relationCount < 0) {
            throw new IllegalArgumentException("relationCount must not be negative");
        }
    }
}

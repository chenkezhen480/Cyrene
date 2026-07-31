package com.harness.graph.model;

public record GraphMutationResult(
        String requestId,
        boolean committed,
        int nodeCount,
        int relationCount
) {
    public GraphMutationResult {
        requestId = GraphModelSupport.requireText(requestId, "requestId");
        if (nodeCount < 0 || relationCount < 0) {
            throw new IllegalArgumentException("mutation counts cannot be negative");
        }
    }
}

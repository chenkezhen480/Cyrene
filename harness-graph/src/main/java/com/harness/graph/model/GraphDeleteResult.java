package com.harness.graph.model;

public record GraphDeleteResult(
        int deletedNodes,
        int deletedRelations
) {
    public GraphDeleteResult {
        if (deletedNodes < 0 || deletedRelations < 0) {
            throw new IllegalArgumentException("delete counts cannot be negative");
        }
    }
}

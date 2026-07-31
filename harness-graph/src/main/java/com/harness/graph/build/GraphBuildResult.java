package com.harness.graph.build;

public record GraphBuildResult(
        String requestId,
        String graphId,
        String schemaId,
        GraphBuildSourceType sourceType,
        String converterId,
        boolean committed,
        int nodeCount,
        int relationCount
) {
}

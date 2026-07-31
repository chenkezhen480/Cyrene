package com.harness.graph.model;

import java.util.Set;

public record GraphNeighborhoodRequest(
        String graphId,
        String schemaId,
        Set<String> subjectIds,
        Set<String> relationTypes,
        int maxDepth,
        int limit
) {
    public GraphNeighborhoodRequest {
        graphId = GraphModelSupport.requireText(graphId, "graphId");
        schemaId = GraphModelSupport.requireText(schemaId, "schemaId");
        if (subjectIds == null || subjectIds.isEmpty()) {
            throw new IllegalArgumentException("subjectIds must contain at least one value");
        }
        subjectIds = Set.copyOf(subjectIds);
        subjectIds.forEach(subjectId -> GraphModelSupport.requireText(subjectId, "subjectId"));
        relationTypes = relationTypes == null ? Set.of() : Set.copyOf(relationTypes);
        relationTypes.forEach(type -> GraphModelSupport.requireText(type, "relationType"));
        maxDepth = GraphModelSupport.requirePositive(maxDepth, "maxDepth");
        limit = GraphModelSupport.requirePositive(limit, "limit");
    }
}

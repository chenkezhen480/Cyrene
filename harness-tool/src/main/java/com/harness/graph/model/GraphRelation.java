package com.harness.graph.model;

import java.util.Map;

public record GraphRelation(
        String relationId,
        String sourceNodeId,
        String targetNodeId,
        String relationType,
        Map<String, Object> properties
) {
    public GraphRelation {
        relationId = GraphModelSupport.requireText(relationId, "relationId");
        sourceNodeId = GraphModelSupport.requireText(sourceNodeId, "sourceNodeId");
        targetNodeId = GraphModelSupport.requireText(targetNodeId, "targetNodeId");
        relationType = GraphModelSupport.requireText(relationType, "relationType");
        properties = GraphModelSupport.copyProperties(properties);
    }
}

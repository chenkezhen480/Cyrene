package com.harness.graph.model;

import java.util.Map;
import java.util.Set;

public record GraphNode(
        String nodeId,
        Set<String> labels,
        Map<String, Object> properties
) {
    public GraphNode {
        nodeId = GraphModelSupport.requireText(nodeId, "nodeId");
        if (labels == null || labels.isEmpty()) {
            throw new IllegalArgumentException("labels must contain at least one value");
        }
        labels = Set.copyOf(labels);
        labels.forEach(label -> GraphModelSupport.requireText(label, "label"));
        properties = GraphModelSupport.copyProperties(properties);
    }
}

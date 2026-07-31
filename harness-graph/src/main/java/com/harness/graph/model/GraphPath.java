package com.harness.graph.model;

import java.util.List;

public record GraphPath(
        List<GraphNode> nodes,
        List<GraphRelation> relations,
        int depth
) {
    public GraphPath {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        relations = relations == null ? List.of() : List.copyOf(relations);
        if (depth < 0) {
            throw new IllegalArgumentException("depth cannot be negative");
        }
        if (depth != relations.size()) {
            throw new IllegalArgumentException("depth must equal relation count");
        }
        if (!relations.isEmpty() && nodes.size() != relations.size() + 1) {
            throw new IllegalArgumentException("a non-empty path must contain one more node than relation");
        }
    }
}

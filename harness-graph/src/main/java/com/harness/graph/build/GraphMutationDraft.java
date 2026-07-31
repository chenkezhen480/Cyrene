package com.harness.graph.build;

import com.harness.graph.model.GraphNode;
import com.harness.graph.model.GraphRelation;

import java.util.List;

public record GraphMutationDraft(
        List<GraphNode> nodes,
        List<GraphRelation> relations
) {
    public GraphMutationDraft {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        relations = relations == null ? List.of() : List.copyOf(relations);
        if (nodes.isEmpty() && relations.isEmpty()) {
            throw new IllegalArgumentException("graph mutation draft must contain nodes or relations");
        }
    }
}

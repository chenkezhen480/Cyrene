package com.harness.graph.model;

import com.harness.core.model.PageInfo;

import java.util.List;
import java.util.Map;

public record GraphRouteResult(
        List<GraphNode> nodes,
        List<GraphRelation> relations,
        List<GraphPath> paths,
        List<GraphAggregate> aggregates,
        PageInfo pageInfo,
        Map<String, Object> metadata
) {
    public GraphRouteResult {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        relations = relations == null ? List.of() : List.copyOf(relations);
        paths = paths == null ? List.of() : List.copyOf(paths);
        aggregates = aggregates == null ? List.of() : List.copyOf(aggregates);
        metadata = GraphModelSupport.copyProperties(metadata);
    }

    public static GraphRouteResult empty() {
        return new GraphRouteResult(List.of(), List.of(), List.of(), List.of(), null, Map.of());
    }

    public boolean isEmpty() {
        return nodes.isEmpty() && relations.isEmpty() && paths.isEmpty() && aggregates.isEmpty();
    }
}

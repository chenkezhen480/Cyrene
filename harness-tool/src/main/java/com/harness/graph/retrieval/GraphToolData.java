package com.harness.graph.retrieval;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Model-facing graph DTOs. Domain graph models never cross the tool serialization boundary. */
public record GraphToolData(
        String graphId,
        String schemaId,
        List<Node> nodes,
        List<Relation> relations,
        List<Path> paths
) {
    public GraphToolData {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        relations = relations == null ? List.of() : List.copyOf(relations);
        paths = paths == null ? List.of() : List.copyOf(paths);
    }

    public record Node(
            String nodeId,
            List<String> labels,
            Map<String, Object> properties
    ) {
        public Node {
            labels = labels == null ? List.of() : List.copyOf(labels);
            properties = properties == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        }
    }

    public record Relation(
            String relationId,
            String sourceNodeId,
            String targetNodeId,
            String relationType,
            Map<String, Object> properties
    ) {
        public Relation {
            properties = properties == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        }
    }

    public record Path(List<Node> nodes, List<Relation> relations, int depth) {
        public Path {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            relations = relations == null ? List.of() : List.copyOf(relations);
        }
    }
}

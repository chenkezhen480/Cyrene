package com.harness.graph.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Null map values explicitly represent objects absent when the draft was created. */
public record GraphMutationBaseline(String schemaHash, Map<String, GraphNode> nodes,
                                    Map<String, GraphRelation> relations,
                                    Map<String, Set<String>> incidentRelationIds) {
    public GraphMutationBaseline {
        GraphModelSupport.requireText(schemaHash, "schemaHash");
        nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
        relations = Collections.unmodifiableMap(new LinkedHashMap<>(relations));
        incidentRelationIds = Map.copyOf(incidentRelationIds);
    }
}

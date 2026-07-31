package com.harness.graph.schema;

import java.util.Map;
import java.util.Set;

public record GraphRelationTypeDefinition(
        String relationType,
        Set<String> sourceLabels,
        Set<String> targetLabels,
        Map<String, GraphPropertyDefinition> properties
) {
    public GraphRelationTypeDefinition {
        GraphSchemaSupport.requireIdentifier(relationType, "relation type");
        if (sourceLabels == null || sourceLabels.isEmpty()) {
            throw new IllegalArgumentException("sourceLabels must contain at least one label");
        }
        if (targetLabels == null || targetLabels.isEmpty()) {
            throw new IllegalArgumentException("targetLabels must contain at least one label");
        }
        sourceLabels = Set.copyOf(sourceLabels);
        targetLabels = Set.copyOf(targetLabels);
        sourceLabels.forEach(label -> GraphSchemaSupport.requireIdentifier(label, "source label"));
        targetLabels.forEach(label -> GraphSchemaSupport.requireIdentifier(label, "target label"));
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        properties.forEach((name, definition) -> {
            if (!name.equals(definition.name())) {
                throw new IllegalArgumentException("relation property key does not match definition name: " + name);
            }
        });
    }
}

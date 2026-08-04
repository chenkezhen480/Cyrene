package com.harness.graph.schema;

import java.util.Map;

public record GraphNodeTypeDefinition(
        String label,
        Map<String, GraphPropertyDefinition> properties
) {
    public GraphNodeTypeDefinition {
        GraphSchemaSupport.requireIdentifier(label, "node label");
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        properties.forEach((name, definition) -> {
            if (!name.equals(definition.name())) {
                throw new IllegalArgumentException("node property key does not match definition name: " + name);
            }
        });
    }
}

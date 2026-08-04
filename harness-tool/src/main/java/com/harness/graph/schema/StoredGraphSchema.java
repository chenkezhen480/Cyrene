package com.harness.graph.schema;

public record StoredGraphSchema(
        boolean enabled,
        GraphSchemaFormat format,
        GraphSchemaDefinition definition
) {
    public StoredGraphSchema {
        if (format == null || format == GraphSchemaFormat.JAVA) {
            throw new IllegalArgumentException("Stored schema format must be JSON or YAML");
        }
        if (definition == null) {
            throw new IllegalArgumentException("definition is required");
        }
    }
}

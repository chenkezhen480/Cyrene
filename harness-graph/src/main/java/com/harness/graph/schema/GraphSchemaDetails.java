package com.harness.graph.schema;

public record GraphSchemaDetails(
        GraphSchemaDefinition definition,
        boolean enabled,
        GraphSchemaSource source,
        GraphSchemaFormat format,
        boolean editable,
        String content
) {
}

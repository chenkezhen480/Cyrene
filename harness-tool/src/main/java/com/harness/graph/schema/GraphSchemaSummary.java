package com.harness.graph.schema;

public record GraphSchemaSummary(
        String schemaId,
        int version,
        GraphSchemaMode mode,
        boolean enabled,
        GraphSchemaSource source,
        GraphSchemaFormat format,
        boolean editable,
        int nodeTypeCount,
        int relationTypeCount
) {
}

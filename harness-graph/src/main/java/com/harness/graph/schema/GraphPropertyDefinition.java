package com.harness.graph.schema;

public record GraphPropertyDefinition(
        String name,
        GraphPropertyType type,
        boolean required,
        boolean sensitive,
        boolean queryable,
        boolean sortable
) {
    public GraphPropertyDefinition {
        GraphSchemaSupport.requireIdentifier(name, "property name");
        if (type == null) {
            throw new IllegalArgumentException("property type is required");
        }
    }
}

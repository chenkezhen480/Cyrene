package com.harness.graph.schema;

import java.util.Set;
import java.util.regex.Pattern;

final class GraphSchemaSupport {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    private static final Pattern SCHEMA_ID = Pattern.compile("[a-z][a-z0-9-]{1,63}");
    private static final Set<String> RESERVED_PROPERTIES = Set.of(
            "storageKey",
            "nodeId",
            "relationId",
            "graphId",
            "schemaId",
            "createdAt",
            "updatedAt"
    );

    private GraphSchemaSupport() {
    }

    static void requireIdentifier(String value, String fieldName) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " must match " + IDENTIFIER.pattern());
        }
        if (RESERVED_PROPERTIES.contains(value)) {
            throw new IllegalArgumentException(fieldName + " is reserved: " + value);
        }
    }

    static void requireSchemaId(String schemaId) {
        if (schemaId == null || !SCHEMA_ID.matcher(schemaId).matches()) {
            throw new IllegalArgumentException("schemaId must match " + SCHEMA_ID.pattern());
        }
    }
}

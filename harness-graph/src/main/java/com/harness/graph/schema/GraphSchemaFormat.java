package com.harness.graph.schema;

import java.util.Locale;

public enum GraphSchemaFormat {
    JSON,
    YAML,
    JAVA;

    public static GraphSchemaFormat parseEditable(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("format is required");
        }
        try {
            GraphSchemaFormat format = valueOf(value.trim().toUpperCase(Locale.ROOT));
            if (format == JAVA) {
                throw new IllegalArgumentException("JAVA schemas are read-only");
            }
            return format;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("format must be JSON or YAML", e);
        }
    }
}

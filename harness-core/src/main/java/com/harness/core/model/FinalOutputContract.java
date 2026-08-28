package com.harness.core.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/** Provider-neutral contract for an agent run's final output. */
public sealed interface FinalOutputContract {

    record Text() implements FinalOutputContract {}

    record JsonSchema(String name, JsonNode schema, boolean strict)
            implements FinalOutputContract {
        public JsonSchema {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Schema name must not be blank");
            }
            Objects.requireNonNull(schema, "schema");
            schema = schema.deepCopy();
        }
    }
}

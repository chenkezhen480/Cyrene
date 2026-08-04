package com.harness.graph.schema;

import java.time.temporal.TemporalAccessor;
import java.util.Collection;
import java.util.Map;

public enum GraphPropertyType {
    STRING,
    BOOLEAN,
    INTEGER,
    LONG,
    DOUBLE,
    NUMBER,
    TEMPORAL,
    STRING_LIST,
    SCALAR_LIST,
    JSON;

    public boolean accepts(Object value) {
        if (value == null) {
            return false;
        }
        return switch (this) {
            case STRING -> value instanceof String;
            case BOOLEAN -> value instanceof Boolean;
            case INTEGER -> value instanceof Integer || value instanceof Short || value instanceof Byte;
            case LONG -> value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte;
            case DOUBLE -> value instanceof Double || value instanceof Float;
            case NUMBER -> value instanceof Number;
            case TEMPORAL -> value instanceof TemporalAccessor;
            case STRING_LIST -> value instanceof Collection<?> collection
                    && collection.stream().allMatch(String.class::isInstance);
            case SCALAR_LIST -> value instanceof Collection<?> collection
                    && collection.stream().allMatch(GraphPropertyType::isScalar);
            case JSON -> value instanceof Map<?, ?> || value instanceof Collection<?>;
        };
    }

    private static boolean isScalar(Object value) {
        return value instanceof String
                || value instanceof Boolean
                || value instanceof Number
                || value instanceof TemporalAccessor;
    }
}

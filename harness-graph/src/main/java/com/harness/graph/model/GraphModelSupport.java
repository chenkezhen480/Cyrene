package com.harness.graph.model;

import java.util.LinkedHashMap;
import java.util.Map;

final class GraphModelSupport {

    private GraphModelSupport() {
    }

    static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }

    static int requirePositive(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be greater than 0");
        }
        return value;
    }

    static Map<String, Object> copyProperties(Map<String, Object> properties) {
        if (properties == null || properties.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        properties.forEach((key, value) -> {
            requireText(key, "property name");
            if (value == null) {
                throw new IllegalArgumentException("Graph properties cannot contain null values: " + key);
            }
            copy.put(key, value);
        });
        return Map.copyOf(copy);
    }
}

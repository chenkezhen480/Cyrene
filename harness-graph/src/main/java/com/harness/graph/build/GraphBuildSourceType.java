package com.harness.graph.build;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum GraphBuildSourceType {
    STRUCTURED("structured"),
    NATURAL_LANGUAGE("natural-language");

    private final String value;

    GraphBuildSourceType(String value) {
        this.value = value;
    }

    @JsonCreator
    public static GraphBuildSourceType fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("sourceType is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (GraphBuildSourceType type : values()) {
            if (type.value.equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException(
                "Unsupported sourceType: " + value + ". Use structured or natural-language");
    }

    @JsonValue
    public String value() {
        return value;
    }
}

package com.harness.graph.config;

import java.util.Locale;

public enum GraphProvider {
    NONE,
    NEO4J;

    public static GraphProvider parse(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported graph provider: " + value, e);
        }
    }
}

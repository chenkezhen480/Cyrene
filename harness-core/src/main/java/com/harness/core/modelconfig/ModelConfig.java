package com.harness.core.modelconfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable, environment-independent snapshot of all model settings. */
public final class ModelConfig {

    private final Map<String, String> values;

    private ModelConfig(Map<String, String> values) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((key, value) -> {
                if (!ModelConfigKey.isKnown(key)) {
                    throw new IllegalArgumentException("Unknown model configuration key: " + key);
                }
                if (value != null && !value.isBlank()) {
                    normalized.put(key, value.trim());
                }
            });
        }
        this.values = Map.copyOf(normalized);
    }

    public static ModelConfig of(Map<String, String> values) { return new ModelConfig(values); }
    public static ModelConfig empty() { return new ModelConfig(Map.of()); }
    public Map<String, String> values() { return values; }
    public String getString(String key) { return values.get(key); }
    public String getString(String key, String defaultValue) {
        String value = values.get(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }
    public String requireString(String key) {
        String value = getString(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required model configuration is not set: " + key);
        }
        return value;
    }
    public int getInt(String key, int defaultValue) {
        String value = getString(key);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw invalidValue(key, "an integer", value, exception);
        }
    }
    public long getLong(String key, long defaultValue) {
        String value = getString(key);
        if (value == null) return defaultValue;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw invalidValue(key, "a long integer", value, exception);
        }
    }
    public double getDouble(String key, double defaultValue) {
        String value = getString(key);
        if (value == null) return defaultValue;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            throw invalidValue(key, "a decimal number", value, exception);
        }
    }
    public boolean getBool(String key, boolean defaultValue) {
        String value = getString(key);
        if (value == null) return defaultValue;
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw invalidValue(key, "true or false", value, null);
    }
    public List<String> getCommaList(String key) {
        String value = getString(key);
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim).filter(item -> !item.isEmpty()).toList();
    }

    private static IllegalArgumentException invalidValue(
            String key,
            String expected,
            String value,
            Exception cause
    ) {
        String message = "Invalid model configuration value for " + key
                + ": expected " + expected + ", got '" + value + "'";
        return cause == null
                ? new IllegalArgumentException(message)
                : new IllegalArgumentException(message, cause);
    }
}

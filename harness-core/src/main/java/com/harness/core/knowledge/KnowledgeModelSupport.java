package com.harness.core.knowledge;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class KnowledgeModelSupport {

    private KnowledgeModelSupport() {
    }

    static String requiredText(String value, String field, int maxLength) {
        String normalized = optionalText(value, field, maxLength);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    static String optionalText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    static <T> List<T> immutableList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    static <T> Set<T> immutableSet(Set<T> values) {
        return values == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(values));
    }

    static Map<String, Object> immutableMap(Map<String, Object> values) {
        return values == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(values));
    }
}

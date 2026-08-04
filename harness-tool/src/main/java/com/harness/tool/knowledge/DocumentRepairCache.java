package com.harness.tool.knowledge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded in-memory cache avoiding repeated visual calls for identical regions.
 */
public final class DocumentRepairCache {

    private final Map<String, Map<String, String>> values;

    public DocumentRepairCache(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.values = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Map<String, String>> eldest) {
                return size() > maxEntries;
            }
        };
    }

    public synchronized Optional<Map<String, String>> get(String key) {
        Map<String, String> value = values.get(key);
        return value != null ? Optional.of(Map.copyOf(value)) : Optional.empty();
    }

    public synchronized void put(String key, Map<String, String> repairs) {
        values.put(key, Map.copyOf(repairs));
    }
}

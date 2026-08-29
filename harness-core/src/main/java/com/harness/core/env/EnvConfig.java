package com.harness.core.env;

import io.github.cdimascio.dotenv.Dotenv;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Environment configuration for infrastructure and non-model runtime settings. */
public final class EnvConfig {
    private static volatile EnvConfig instance;
    private final Map<String, String> store;

    private EnvConfig(Map<String, String> overrides) {
        this.store = new ConcurrentHashMap<>();
        try {
            Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
            for (var entry : dotenv.entries()) {
                if (entry.getKey().startsWith("HARNESS_")) store.putIfAbsent(entry.getKey(), entry.getValue());
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to load working-directory .env", exception);
        }
        System.getenv().forEach((key, value) -> {
            if (key.startsWith("HARNESS_")) store.put(key, value);
        });
        if (overrides != null) store.putAll(overrides);
    }

    public static void init(Map<String, String> overrides) { instance = new EnvConfig(overrides); }
    public static EnvConfig get() {
        if (instance == null) init(Collections.emptyMap());
        return instance;
    }
    public String getString(String key, String defaultValue) { return store.getOrDefault(key, defaultValue); }
    public String getString(String key) { return getString(key, null); }
    public String requireString(String key) {
        String value = store.get(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Required env var not set: " + key);
        return value;
    }
    public int getInt(String key, int defaultValue) {
        String value = store.get(key);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value.trim());
    }
    public long getLong(String key, long defaultValue) {
        String value = store.get(key);
        return value == null || value.isBlank() ? defaultValue : Long.parseLong(value.trim());
    }
    public double getDouble(String key, double defaultValue) {
        String value = store.get(key);
        return value == null || value.isBlank() ? defaultValue : Double.parseDouble(value.trim());
    }
    public boolean getBool(String key, boolean defaultValue) {
        String value = store.get(key);
        return value == null || value.isBlank() ? defaultValue : Boolean.parseBoolean(value.trim());
    }
    public List<String> getList(String key, String separator) {
        String value = store.get(key);
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(separator)).map(String::trim)
                .filter(item -> !item.isEmpty()).toList();
    }
    public List<String> getCommaList(String key) { return getList(key, ","); }
    public void set(String key, String value) { store.put(key, value); }
    public Map<String, String> all() { return Collections.unmodifiableMap(store); }

    /** HikariCP defaults shared by PostgreSQL and MySQL pools. */
    public static void applyDefaultPoolSettings(com.zaxxer.hikari.HikariConfig config, String poolName) {
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(5000);
        config.setIdleTimeout(300_000);
        config.setMaxLifetime(600_000);
        config.setPoolName(poolName);
    }
}

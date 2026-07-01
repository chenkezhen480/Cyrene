package com.harness.env;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.cdimascio.dotenv.Dotenv;

/**
 * Centralized environment configuration.
 * Loads all HARNESS_* variables at startup, provides typed accessors with defaults.
 */
public final class EnvConfig {

    private static volatile EnvConfig instance;
    private final Map<String, String> store;

    private EnvConfig(Map<String, String> overrides) {
        this.store = new ConcurrentHashMap<>();
        // Load .env file as fallback (silently skip if missing)
        try {
            Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
            for (var entry : dotenv.entries()) {
                if (entry.getKey().startsWith("HARNESS_")) {
                    store.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        } catch (Exception e) {
            // .env file not present or unparseable — continue with system env
        }
        // System env takes precedence over .env
        System.getenv().forEach((k, v) -> {
            if (k.startsWith("HARNESS_")) {
                store.put(k, v);
            }
        });
        // Overrides take highest precedence
        if (overrides != null) {
            store.putAll(overrides);
        }
    }

    public static void init(Map<String, String> overrides) {
        instance = new EnvConfig(overrides);
    }

    public static EnvConfig get() {
        if (instance == null) {
            init(Collections.emptyMap());
        }
        return instance;
    }

    // ==================== Typed Accessors ====================

    public String getString(String key, String defaultValue) {
        return store.getOrDefault(key, defaultValue);
    }

    public String getString(String key) {
        return getString(key, null);
    }

    public String requireString(String key) {
        String val = store.get(key);
        if (val == null || val.isBlank()) {
            throw new IllegalStateException("Required env var not set: " + key);
        }
        return val;
    }

    public int getInt(String key, int defaultValue) {
        String val = store.get(key);
        if (val == null || val.isBlank()) return defaultValue;
        return Integer.parseInt(val.trim());
    }

    public long getLong(String key, long defaultValue) {
        String val = store.get(key);
        if (val == null || val.isBlank()) return defaultValue;
        return Long.parseLong(val.trim());
    }

    public double getDouble(String key, double defaultValue) {
        String val = store.get(key);
        if (val == null || val.isBlank()) return defaultValue;
        return Double.parseDouble(val.trim());
    }

    public boolean getBool(String key, boolean defaultValue) {
        String val = store.get(key);
        if (val == null || val.isBlank()) return defaultValue;
        return Boolean.parseBoolean(val.trim());
    }

    public List<String> getList(String key, String separator) {
        String val = store.get(key);
        if (val == null || val.isBlank()) return Collections.emptyList();
        return Arrays.stream(val.split(separator))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public List<String> getCommaList(String key) {
        return getList(key, ",");
    }

    /**
     * Override a value at runtime (useful for testing).
     */
    public void set(String key, String value) {
        store.put(key, value);
    }

    public Map<String, String> all() {
        return Collections.unmodifiableMap(store);
    }

    // ==================== 连接池共享配置 ====================
    /** HikariCP 默认连接池参数，Pg/Mysql 共用 */
    public static void applyDefaultPoolSettings(com.zaxxer.hikari.HikariConfig hc, String poolName) {
        hc.setMaximumPoolSize(10);
        hc.setMinimumIdle(2);
        hc.setConnectionTimeout(5000);
        hc.setIdleTimeout(300_000);   // 5 min
        hc.setMaxLifetime(600_000);   // 10 min
        hc.setPoolName(poolName);
    }
}

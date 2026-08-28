package com.harness.core.env;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.cdimascio.dotenv.Dotenv;

/**
 * Centralized environment configuration.
 * Loads HARNESS_* values at startup and keeps Web-managed model overrides mutable.
 * Precedence for model keys: Web-managed file > explicit overrides > process env > .env.
 */
public final class EnvConfig {

    private static volatile EnvConfig instance;
    private final Map<String, String> baseStore;
    private final Map<String, String> explicitOverrides;
    private final Map<String, String> store;
    private volatile Map<String, String> managedModelOverrides;

    private EnvConfig(Map<String, String> overrides) {
        Map<String, String> baseValues = new LinkedHashMap<>();
        // Load .env file as fallback (silently skip if missing)
        try {
            Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
            for (var entry : dotenv.entries()) {
                if (entry.getKey().startsWith("HARNESS_")) {
                    baseValues.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        } catch (Exception e) {
            // .env file not present or unparseable — continue with system env
        }
        // System env takes precedence over .env
        System.getenv().forEach((k, v) -> {
            if (k.startsWith("HARNESS_")) {
                baseValues.put(k, v);
            }
        });
        this.baseStore = Map.copyOf(baseValues);
        this.explicitOverrides = overrides == null ? Map.of() : Map.copyOf(overrides);
        this.managedModelOverrides = loadManagedModelOverrides(
                configuredModelFile(baseValues, this.explicitOverrides));
        this.store = new ConcurrentHashMap<>();
        rebuildStore();
    }

    private EnvConfig(
            Map<String, String> baseStore,
            Map<String, String> explicitOverrides,
            Map<String, String> managedModelOverrides
    ) {
        this.baseStore = Map.copyOf(baseStore);
        this.explicitOverrides = Map.copyOf(explicitOverrides);
        this.managedModelOverrides = normalizeModelOverrides(managedModelOverrides);
        this.store = new ConcurrentHashMap<>();
        rebuildStore();
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

    /** Build an isolated candidate configuration without mutating the active runtime. */
    public EnvConfig previewModelOverrides(Map<String, String> modelOverrides) {
        return new EnvConfig(baseStore, explicitOverrides, modelOverrides);
    }

    /** Publish the Web-managed model overrides after new providers are ready. */
    public synchronized void replaceManagedModelOverrides(Map<String, String> modelOverrides) {
        this.managedModelOverrides = normalizeModelOverrides(modelOverrides);
        rebuildStore();
    }

    public Map<String, String> managedModelOverrides() {
        return managedModelOverrides;
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

    private synchronized void rebuildStore() {
        store.clear();
        store.putAll(baseStore);
        store.putAll(explicitOverrides);
        // Web-managed model values are explicit runtime choices and win for model keys.
        store.putAll(managedModelOverrides);
    }

    private static Path configuredModelFile(
            Map<String, String> baseValues,
            Map<String, String> overrides
    ) {
        String configured = overrides.getOrDefault(
                EnvKey.CONFIG_MODEL_FILE,
                baseValues.getOrDefault(
                        EnvKey.CONFIG_MODEL_FILE,
                        "./data/model-config.env"));
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private static Map<String, String> loadManagedModelOverrides(Path path) {
        try {
            Path parent = path.getParent();
            Path fileName = path.getFileName();
            if (parent == null || fileName == null) {
                return Map.of();
            }
            Dotenv dotenv = Dotenv.configure()
                    .directory(parent.toString())
                    .filename(fileName.toString())
                    .ignoreIfMissing()
                    .load();
            Map<String, String> values = new LinkedHashMap<>();
            for (var entry : dotenv.entries()) {
                if (isManagedModelKey(entry.getKey())) {
                    values.put(entry.getKey(), entry.getValue());
                }
            }
            return Map.copyOf(values);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Unable to load Web-managed model configuration: " + path,
                    exception);
        }
    }

    private static Map<String, String> normalizeModelOverrides(Map<String, String> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        overrides.forEach((key, value) -> {
            if (isManagedModelKey(key) && value != null && !value.isBlank()) {
                normalized.put(key, value);
            }
        });
        return Map.copyOf(normalized);
    }

    private static boolean isManagedModelKey(String key) {
        return key != null && (key.startsWith("HARNESS_MODEL_")
                || key.startsWith("HARNESS_RERANK_")
                || key.startsWith("HARNESS_TOOL_IMAGE_GEN_")
                || key.startsWith("HARNESS_TOOL_VIDEO_GEN_"));
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

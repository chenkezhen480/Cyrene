package com.harness.preprocess.memory;

import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating memory store instances based on HARNESS_AUDIT_STORE env var.
 * Shared with TraceStoreFactory — one storage type controls both memory and trace.
 * Supported backends: mysql, none (default).
 * When set to "none", all stores return NoOp implementations.
 */
public final class MemoryStoreFactory {

    private static final Logger log = LoggerFactory.getLogger(MemoryStoreFactory.class);

    private MemoryStoreFactory() {}

    public static SessionStore createSessionStore() {
        String store = EnvConfig.get().getString(EnvKey.AUDIT_STORE, "none");
        return switch (store.toLowerCase()) {
            case "mysql" -> new MysqlSessionStore();
            case "none" -> new NoOpSessionStore();
            default -> throw new IllegalStateException("Unknown memory store: " + store);
        };
    }

    public static MessageStore createMessageStore() {
        String store = EnvConfig.get().getString(EnvKey.AUDIT_STORE, "none");
        return switch (store.toLowerCase()) {
            case "mysql" -> new MysqlMessageStore();
            case "none" -> new NoOpMessageStore();
            default -> throw new IllegalStateException("Unknown memory store: " + store);
        };
    }

    public static PreferenceStore createPreferenceStore() {
        String store = EnvConfig.get().getString(EnvKey.AUDIT_STORE, "none");
        return switch (store.toLowerCase()) {
            case "mysql" -> new MysqlPreferenceStore();
            case "none" -> new NoOpPreferenceStore();
            default -> throw new IllegalStateException("Unknown memory store: " + store);
        };
    }

    /**
     * Returns true if memory store is enabled (not "none").
     */
    public static boolean isEnabled() {
        String store = EnvConfig.get().getString(EnvKey.AUDIT_STORE, "none");
        return !"none".equalsIgnoreCase(store);
    }

    /**
     * Create message cache — Redis if HARNESS_MEMORY_REDIS_URL is set, otherwise in-memory.
     */
    public static SessionMessageCache createMessageCache() {
        String redisUrl = EnvConfig.get().getString(EnvKey.MEMORY_REDIS_URL);
        if (redisUrl != null && !redisUrl.isBlank()) {
            log.info("[Memory] Using Redis session message cache: {}", redisUrl);
            return new RedisSessionMessageCache();
        }
        return new InMemorySessionMessageCache();
    }
}

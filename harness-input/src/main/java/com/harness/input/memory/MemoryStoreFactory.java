package com.harness.input.memory;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;

/**
 * Factory for creating memory stores based on the independent HARNESS_MEMORY_STORE setting.
 * Supported backends: mysql, none (default).
 * When set to "none", all stores return NoOp implementations.
 */
public final class MemoryStoreFactory {

    private MemoryStoreFactory() {}

    public static SessionStore createSessionStore() {
        String store = storeType();
        return switch (store.toLowerCase()) {
            case "mysql" -> new MysqlSessionStore();
            case "none" -> new NoOpSessionStore();
            default -> throw new IllegalStateException("Unknown memory store: " + store);
        };
    }

    public static MessageStore createMessageStore() {
        String store = storeType();
        return switch (store.toLowerCase()) {
            case "mysql" -> new MysqlMessageStore();
            case "none" -> new NoOpMessageStore();
            default -> throw new IllegalStateException("Unknown memory store: " + store);
        };
    }

    /**
     * Returns true if memory store is enabled (not "none").
     */
    public static boolean isEnabled() {
        return !"none".equalsIgnoreCase(storeType());
    }

    public static boolean isMysqlEnabled() {
        return "mysql".equalsIgnoreCase(storeType());
    }

    /**
     * Create message cache — Redis if HARNESS_MEMORY_REDIS_URL is set, otherwise in-memory.
     */
    public static SessionMessageCache createMessageCache() {
        String redisUrl = EnvConfig.get().getString(EnvKey.MEMORY_REDIS_URL);
        if (redisUrl != null && !redisUrl.isBlank()) {

            return new RedisSessionMessageCache();
        }
        return new InMemorySessionMessageCache();
    }

    private static String storeType() {
        return EnvConfig.get().getString(EnvKey.MEMORY_STORE, "none").trim();
    }
}

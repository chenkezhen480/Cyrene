package com.harness.preprocess.memory;

import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;

/**
 * Factory for creating memory store instances based on HARNESS_MEMORY_STORE env var.
 * Supported backends: mysql, sqlite, none (default).
 * When set to "none", all stores return NoOp implementations.
 */
public final class MemoryStoreFactory {

    private MemoryStoreFactory() {}

    public static SessionStore createSessionStore() {
        String store = EnvConfig.get().getString(EnvKey.MEMORY_STORE, "none");
        return switch (store.toLowerCase()) {
            case "mysql" -> new MysqlSessionStore();
            case "sqlite" -> new MysqlSessionStore(); // TODO: implement SqliteSessionStore
            case "none" -> new NoOpSessionStore();
            default -> throw new IllegalStateException("Unknown memory store: " + store);
        };
    }

    public static MessageStore createMessageStore() {
        String store = EnvConfig.get().getString(EnvKey.MEMORY_STORE, "none");
        return switch (store.toLowerCase()) {
            case "mysql" -> new MysqlMessageStore();
            case "sqlite" -> new MysqlMessageStore(); // TODO: implement SqliteMessageStore
            case "none" -> new NoOpMessageStore();
            default -> throw new IllegalStateException("Unknown memory store: " + store);
        };
    }

    public static PreferenceStore createPreferenceStore() {
        String store = EnvConfig.get().getString(EnvKey.MEMORY_STORE, "none");
        return switch (store.toLowerCase()) {
            case "mysql" -> new MysqlPreferenceStore();
            case "sqlite" -> new MysqlPreferenceStore(); // TODO: implement SqlitePreferenceStore
            case "none" -> new NoOpPreferenceStore();
            default -> throw new IllegalStateException("Unknown memory store: " + store);
        };
    }

    /**
     * Returns true if memory store is enabled (not "none").
     */
    public static boolean isEnabled() {
        String store = EnvConfig.get().getString(EnvKey.MEMORY_STORE, "none");
        return !"none".equalsIgnoreCase(store);
    }
}

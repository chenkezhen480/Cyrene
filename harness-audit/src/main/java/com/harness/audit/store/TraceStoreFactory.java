package com.harness.audit.store;

import com.harness.core.model.AgentTrace;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;

/**
 * Creates TraceStore instances based on HARNESS_AUDIT_STORE env var.
 */
public final class TraceStoreFactory {

    private TraceStoreFactory() {}

    public static TraceStore create() {
        String store = EnvConfig.get().getString(EnvKey.AUDIT_STORE, "sqlite");
        return switch (store.toLowerCase()) {
            case "sqlite" -> new SqliteTraceStore();
            case "mysql" -> new MysqlTraceStore();
            case "file" -> new FileTraceStore();
            case "none" -> new NoOpTraceStore();
            default -> throw new IllegalStateException("Unknown audit store: " + store);
        };
    }

    /**
     * No-op store for when auditing is disabled.
     */
    static class NoOpTraceStore implements TraceStore {
        @Override public void save(AgentTrace trace) {}
        @Override public java.util.Optional<AgentTrace> findById(String traceId) { return java.util.Optional.empty(); }
        @Override public java.util.List<AgentTrace> listRecent(int limit) { return java.util.List.of(); }
        @Override public int cleanup(int retentionDays) { return 0; }
        @Override public boolean deleteById(String traceId) { return false; }
        @Override public int count() { return 0; }
        @Override public void updateMetadata(String traceId, java.util.Map<String, String> entries) {}
        @Override public void close() {}
    }
}

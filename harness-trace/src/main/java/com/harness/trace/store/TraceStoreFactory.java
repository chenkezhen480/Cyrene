package com.harness.trace.store;

import com.harness.core.model.AgentTrace;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;

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
        @Override public com.harness.core.model.PageResponse<AgentTrace> findBySession(
                String sessionId, com.harness.core.model.TraceCursor cursor, int limit) {
            return new com.harness.core.model.PageResponse<>(
                    java.util.List.of(),
                    new com.harness.core.model.PageInfo(limit, "", false));
        }
        @Override public CleanupResult cleanup(
                int retentionDays, java.util.function.Predicate<String> retainedByKnowledge) {
            return new CleanupResult(0, 0);
        }
        @Override public boolean deleteById(String traceId) { return false; }
        @Override public int count() { return 0; }
        @Override public boolean updateMetadata(String traceId, java.util.Map<String, String> entries) { return false; }
        @Override public void close() {}
    }
}

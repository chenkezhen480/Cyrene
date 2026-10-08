package com.harness.trace.store;

import com.harness.core.model.AgentTrace;
import com.harness.core.model.PageResponse;
import com.harness.core.model.TraceCursor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteTraceStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void constructorRejectsMissingSchemaWithoutCreatingTables() throws Exception {
        String dbUrl = "jdbc:sqlite:" + tempDir.resolve("missing-schema.db").toAbsolutePath();

        assertThatThrownBy(() -> new SqliteTraceStore(dbUrl))
                .isInstanceOf(TraceStoreException.class)
                .hasMessageContaining("sql/schema-sqlite.sql");

        try (var connection = DriverManager.getConnection(dbUrl);
             var statement = connection.createStatement();
             var result = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'agent_traces'")) {
            assertThat(result.next()).isFalse();
        }
    }

    @Test
    void findBySessionUsesStableTimestampAndTraceIdCursor() throws Exception {
        SqliteTraceStore store = initializedStore("trace.db");
        Instant newest = Instant.parse("2026-09-01T05:00:00Z");
        Instant older = Instant.parse("2026-09-01T04:00:00Z");
        store.save(trace("trace-c", "session-1", newest));
        store.save(trace("trace-b", "session-1", newest));
        store.save(trace("trace-a", "session-1", newest));
        store.save(trace("trace-z", "session-1", older));
        store.save(trace("other", "session-2", newest));

        PageResponse<AgentTrace> first = store.findBySession("session-1", null, 2);

        assertThat(first.items()).extracting(AgentTrace::traceId)
                .containsExactly("trace-c", "trace-b");
        assertThat(first.pageInfo().hasMore()).isTrue();
        assertThat(first.pageInfo().nextCursor())
                .isEqualTo(newest + "|trace-b");

        PageResponse<AgentTrace> second = store.findBySession(
                "session-1", new TraceCursor(newest, "trace-b"), 2);

        assertThat(second.items()).extracting(AgentTrace::traceId)
                .containsExactly("trace-a", "trace-z");
        assertThat(second.pageInfo().hasMore()).isFalse();
        assertThat(second.pageInfo().nextCursor()).isEmpty();
    }

    @Test
    void pagesOrderVariablePrecisionInstantsWithoutSkippingLegacyRows() throws Exception {
        var store = initializedStore("precision.db");
        var times = java.util.List.of("2026-09-01T05:00:00Z", "2026-09-01T05:00:00.100Z",
                "2026-09-01T05:00:00.100001Z", "2026-09-01T05:00:00.100001001Z");
        for (int i = 0; i < times.size(); i++) {
            store.save(AgentTrace.builder().traceId("precision-" + i).sessionId("session").userId("user")
                    .timestamp(Instant.parse(times.get(i))).metadata(java.util.Map.of("tenant_id", "tenant")).build());
        }
        var first = store.findByOwner("user", "tenant", null, 2);
        assertThat(first.items()).extracting(AgentTrace::traceId).containsExactly("precision-3", "precision-2");
        var cursor = new TraceCursor(Instant.parse(times.get(2)), "precision-2");
        assertThat(store.findByOwner("user", "tenant", cursor, 2).items())
                .extracting(AgentTrace::traceId).containsExactly("precision-1", "precision-0");
        assertThat(store.findBySession("session", cursor, 2).items())
                .extracting(AgentTrace::traceId).containsExactly("precision-1", "precision-0");
        assertThat(store.listRecent(4)).extracting(AgentTrace::traceId)
                .containsExactly("precision-3", "precision-2", "precision-1", "precision-0");
    }

    @Test
    void ownerPagesAndTransactionalCleanupAreTenantScoped() throws Exception {
        var store = initializedStore("owner.db");
        Instant old = Instant.parse("2000-01-01T00:00:00Z");
        for (String id : java.util.List.of("a", "b", "c")) {
            store.save(AgentTrace.builder().traceId(id).sessionId("session").userId("user-a")
                    .timestamp(old).metadata(java.util.Map.of("tenant_id", "tenant-a")).build());
        }
        store.save(AgentTrace.builder().traceId("foreign-tenant").userId("user-a")
                .timestamp(old).metadata(java.util.Map.of("tenant_id", "tenant-b")).build());
        store.save(AgentTrace.builder().traceId("foreign-user").userId("user-b")
                .timestamp(old).metadata(java.util.Map.of("tenant_id", "tenant-a")).build());
        store.save(AgentTrace.builder().traceId("legacy").userId("user-a").timestamp(old).build());
        var first = store.findByOwner("user-a", "tenant-a", null, 2);
        assertThat(first.items()).extracting(AgentTrace::traceId).containsExactly("c", "b");
        assertThat(first.pageInfo().hasMore()).isTrue();
        assertThat(store.findByOwner("user-a", "tenant-a", new TraceCursor(old, "b"), 2).items())
                .extracting(AgentTrace::traceId).containsExactly("a");
        assertThat(store.countByOwner("user-a", "tenant-a")).isEqualTo(3);
        assertThat(store.countByOwner("User-a", "tenant-a")).isZero();
        assertThat(store.cleanupByOwner("user-a", "tenant-a", 1)).isEqualTo(3);
        assertThat(store.findById("foreign-tenant")).isPresent();
        assertThat(store.findById("foreign-user")).isPresent();
        assertThat(store.findById("legacy")).isPresent();
    }

    @Test
    void cleanup_removesAllExpiredTraces() throws Exception {
        SqliteTraceStore store = initializedStore("cleanup.db");
        Instant old = Instant.parse("2000-01-01T00:00:00Z");
        store.save(trace("expired-1", "session-1", old));
        store.save(trace("expired-2", "session-1", old));

        int deleted = store.cleanup(1);

        assertThat(deleted).isEqualTo(2);
        assertThat(store.findById("expired-1")).isEmpty();
        assertThat(store.findById("expired-2")).isEmpty();
    }

    @Test void ownerPageUsesIndexWithoutTemporarySort() throws Exception {
        initializedStore("indexed.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("indexed.db"));
             var statement = connection.createStatement();
             var rows = statement.executeQuery("EXPLAIN QUERY PLAN SELECT full_json FROM agent_traces "
                     + "WHERE user_id='user-a' AND json_extract(full_json, '$.metadata.tenant_id') IS 'tenant-a' "
                     + "ORDER BY " + SqliteTraceStore.TIMESTAMP_ORDER + " DESC, trace_id DESC LIMIT 51")) {
            var plans = new java.util.ArrayList<String>();
            while (rows.next()) plans.add(rows.getString("detail"));
            assertThat(plans).anyMatch(plan -> plan.contains("idx_trace_owner_time"));
            assertThat(plans).noneMatch(plan -> plan.contains("TEMP B-TREE") || plan.startsWith("SCAN"));
        }
    }

    private SqliteTraceStore initializedStore(String fileName) throws Exception {
        String dbUrl = "jdbc:sqlite:" + tempDir.resolve(fileName).toAbsolutePath();
        String schema = Files.readString(Path.of("..", "sql", "schema-sqlite.sql"));
        try (var connection = DriverManager.getConnection(dbUrl);
             var statement = connection.createStatement()) {
            for (String command : schema.split(";")) {
                if (!command.isBlank()) statement.executeUpdate(command);
            }
        }
        return new SqliteTraceStore(dbUrl);
    }

    private static AgentTrace trace(String traceId, String sessionId, Instant timestamp) {
        return AgentTrace.builder()
                .traceId(traceId)
                .sessionId(sessionId)
                .timestamp(timestamp)
                .build();
    }

    @Test
    void corruptReadsAreStorageErrorsAndMissingIdsStayAbsent() throws Exception {
        var store = initializedStore("corrupt.db");
        store.save(trace("corrupt", "session", Instant.EPOCH));
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("corrupt.db").toAbsolutePath());
             var sql = conn.createStatement()) {
            sql.executeUpdate("UPDATE agent_traces SET full_json = '[]' WHERE trace_id = 'corrupt'");
        }
        assertThatThrownBy(() -> store.findById("corrupt")).isInstanceOf(TraceStoreException.class);
        assertThat(store.findById("missing")).isEmpty();
    }
}

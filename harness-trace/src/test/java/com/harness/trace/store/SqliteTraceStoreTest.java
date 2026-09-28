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

    private SqliteTraceStore initializedStore(String fileName) throws Exception {
        String dbUrl = "jdbc:sqlite:" + tempDir.resolve(fileName).toAbsolutePath();
        String schema = Files.readString(Path.of("..", "sql", "schema-sqlite.sql"));
        try (var connection = DriverManager.getConnection(dbUrl);
             var statement = connection.createStatement()) {
            statement.executeUpdate(schema);
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
}

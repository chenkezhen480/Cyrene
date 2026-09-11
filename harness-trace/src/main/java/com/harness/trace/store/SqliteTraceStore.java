package com.harness.trace.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.harness.core.model.AgentTrace;
import com.harness.core.model.PageResponse;
import com.harness.core.model.TraceCursor;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * SQLite-based trace store. Default implementation.
 * Configured via HARNESS_AUDIT_* environment variables.
 */
public class SqliteTraceStore implements TraceStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteTraceStore.class);
    private final ObjectMapper mapper;
    private final String dbUrl;

    public SqliteTraceStore() {
        this(EnvConfig.get().getString(EnvKey.AUDIT_DB_URL, "jdbc:sqlite:harness_trace.db"));
    }

    SqliteTraceStore(String dbUrl) {
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        if (dbUrl == null || dbUrl.isBlank()) {
            throw new IllegalArgumentException("dbUrl is required");
        }
        this.dbUrl = dbUrl;
        initTable();
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(dbUrl);
    }

    private void initTable() {
        String sql = """
                CREATE TABLE IF NOT EXISTS agent_traces (
                    trace_id    TEXT PRIMARY KEY,
                    timestamp   TEXT NOT NULL,
                    user_id     TEXT,
                    session_id  TEXT,
                    input_text  TEXT,
                    intent      TEXT,
                    llm_model   TEXT,
                    steps_json  TEXT,
                    final_output TEXT,
                    risk_level  TEXT,
                    total_duration_ms INTEGER,
                    total_tokens INTEGER,
                    full_json   TEXT NOT NULL
                )
                """;
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            log.info("Trace store initialized: {}", dbUrl);
        } catch (SQLException e) {
            log.error("Failed to init trace table: {}", e.getMessage(), e);
            throw new TraceStoreException("Failed to initialize trace table", e);
        }
    }

    @Override
    public void save(AgentTrace trace) {
        String sql = """
                INSERT OR REPLACE INTO agent_traces
                (trace_id, timestamp, user_id, session_id, input_text, intent, llm_model, steps_json, final_output, risk_level, total_duration_ms, total_tokens, full_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            String fullJson = mapper.writeValueAsString(trace);
            String stepsJson = mapper.writeValueAsString(trace.steps());

            ps.setString(1, trace.traceId());
            ps.setString(2, trace.timestamp().toString());
            ps.setString(3, trace.userId());
            ps.setString(4, trace.sessionId());
            ps.setString(5, trace.inputText());
            ps.setString(6, trace.intent());
            ps.setString(7, trace.llmModel());
            ps.setString(8, stepsJson);
            ps.setString(9, trace.finalOutput());
            ps.setString(10, trace.riskLevel().name());
            ps.setLong(11, trace.totalDurationMs());
            ps.setInt(12, trace.totalTokens());
            ps.setString(13, fullJson);
            ps.executeUpdate();
        } catch (SQLException | JsonProcessingException e) {
            log.error("Failed to save trace {}: {}", trace.traceId(), e.getMessage(), e);
            throw new TraceStoreException("Failed to save trace " + trace.traceId(), e);
        }
    }

    @Override
    public Optional<AgentTrace> findById(String traceId) {
        String sql = "SELECT full_json FROM agent_traces WHERE trace_id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, traceId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(mapper.readValue(rs.getString("full_json"), AgentTrace.class));
            }
        } catch (Exception e) {
            log.error("Failed to find trace {}: {}", traceId, e.getMessage(), e);
        }
        return Optional.empty();
    }

    @Override
    public List<AgentTrace> listRecent(int limit) {
        String sql = "SELECT full_json FROM agent_traces ORDER BY timestamp DESC LIMIT ?";
        List<AgentTrace> results = new ArrayList<>();
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                results.add(mapper.readValue(rs.getString("full_json"), AgentTrace.class));
            }
        } catch (Exception e) {
            log.error("Failed to list traces: {}", e.getMessage(), e);
        }
        return results;
    }

    @Override
    public PageResponse<AgentTrace> findBySession(String sessionId, TraceCursor cursor, int limit) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        String sql = cursor == null
                ? "SELECT full_json FROM agent_traces WHERE session_id = ? "
                        + "ORDER BY timestamp DESC, trace_id DESC LIMIT ?"
                : "SELECT full_json FROM agent_traces WHERE session_id = ? "
                        + "AND (timestamp < ? OR (timestamp = ? AND trace_id < ?)) "
                        + "ORDER BY timestamp DESC, trace_id DESC LIMIT ?";
        List<AgentTrace> results = new ArrayList<>();
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            if (cursor == null) {
                statement.setInt(2, limit + 1);
            } else {
                String timestamp = cursor.timestamp().toString();
                statement.setString(2, timestamp);
                statement.setString(3, timestamp);
                statement.setString(4, cursor.traceId());
                statement.setInt(5, limit + 1);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    results.add(mapper.readValue(resultSet.getString("full_json"), AgentTrace.class));
                }
            }
        } catch (Exception e) {
            throw new TraceStoreException("Failed to list traces for session " + sessionId, e);
        }
        return PageResponse.fromFetched(
                results,
                limit,
                trace -> trace.timestamp() + "|" + trace.traceId());
    }

    @Override
    public CleanupResult cleanup(
            int retentionDays,
            Predicate<String> retainedByKnowledge
    ) {
        java.util.Objects.requireNonNull(retainedByKnowledge, "retainedByKnowledge");
        Instant cutoff = Instant.now().minusSeconds(retentionDays * 86400L);
        String deleteSql = "DELETE FROM agent_traces WHERE trace_id = ? AND timestamp < ?";
        Connection connection = null;
        try {
            connection = getConnection();
            connection.setAutoCommit(false);
            int retained = 0;
            int deleted = 0;
            ExpiredTraceCursor cursor = null;
            boolean hasMore;
            do {
                List<ExpiredTraceCursor> fetched = findExpiredPage(
                        connection, cutoff.toString(), cursor, 500);
                hasMore = fetched.size() > 500;
                List<ExpiredTraceCursor> page = hasMore
                        ? fetched.subList(0, 500)
                        : fetched;
                try (PreparedStatement statement = connection.prepareStatement(deleteSql)) {
                    for (ExpiredTraceCursor candidate : page) {
                        if (retainedByKnowledge.test(candidate.traceId())) {
                            retained++;
                            continue;
                        }
                        statement.setString(1, candidate.traceId());
                        statement.setString(2, cutoff.toString());
                        deleted += statement.executeUpdate();
                    }
                }
                cursor = page.isEmpty() ? cursor : page.getLast();
            } while (hasMore);
            connection.commit();
            return new CleanupResult(deleted, retained);
        } catch (SQLException | RuntimeException e) {
            rollback(connection);
            log.error("Failed to cleanup traces: {}", e.getMessage(), e);
            throw new TraceStoreException("Failed to cleanup traces", e);
        } finally {
            closeCleanupConnection(connection);
        }
    }

    private static List<ExpiredTraceCursor> findExpiredPage(
            Connection connection,
            String cutoff,
            ExpiredTraceCursor cursor,
            int limit
    ) throws SQLException {
        String sql = cursor == null
                ? """
                SELECT trace_id, timestamp FROM agent_traces
                WHERE timestamp < ?
                ORDER BY timestamp ASC, trace_id ASC LIMIT ?
                """
                : """
                SELECT trace_id, timestamp FROM agent_traces
                WHERE timestamp < ?
                  AND (timestamp > ? OR (timestamp = ? AND trace_id > ?))
                ORDER BY timestamp ASC, trace_id ASC LIMIT ?
                """;
        List<ExpiredTraceCursor> fetched = new ArrayList<>(limit + 1);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, cutoff);
            if (cursor == null) {
                statement.setInt(2, limit + 1);
            } else {
                statement.setString(2, cursor.timestamp());
                statement.setString(3, cursor.timestamp());
                statement.setString(4, cursor.traceId());
                statement.setInt(5, limit + 1);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    fetched.add(new ExpiredTraceCursor(
                            resultSet.getString("timestamp"),
                            resultSet.getString("trace_id")));
                }
            }
        }
        return fetched;
    }

    @Override
    public boolean deleteById(String traceId) {
        String sql = "DELETE FROM agent_traces WHERE trace_id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, traceId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete trace {}: {}", traceId, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public int count() {
        String sql = "SELECT COUNT(*) FROM agent_traces";
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            log.error("Failed to count traces: {}", e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public synchronized boolean updateMetadata(String traceId, Map<String, String> entries) {
        // Read-modify-write: load full_json, merge metadata, save back
        Optional<AgentTrace> found = findById(traceId);
        if (found.isEmpty()) {
            return false;
        }
        AgentTrace trace = found.get();
        Map<String, String> merged = new HashMap<>(trace.metadata());
        merged.putAll(entries);
        AgentTrace updated = new AgentTrace(
                trace.traceId(), trace.timestamp(), trace.userId(), trace.sessionId(),
                trace.inputText(), trace.inputAttachments(), trace.intent(), trace.ragHits(),
                trace.rerankResult(), trace.llmModel(), trace.promptVersion(), trace.steps(),
                trace.finalOutput(), trace.riskLevel(), trace.userConfirmed(),
                trace.totalDurationMs(), trace.totalTokens(), merged);
        save(updated);
        return true;
    }

    @Override
    public void close() {
        // SQLite connections are auto-closed
    }

    private static void rollback(Connection connection) {
        if (connection == null) return;
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.error("Failed to rollback trace cleanup: {}", e.getMessage(), e);
        }
    }

    private static void closeCleanupConnection(Connection connection) {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException e) {
            log.error("Failed to close trace cleanup connection: {}", e.getMessage(), e);
        }
    }

    private record ExpiredTraceCursor(String timestamp, String traceId) {
    }
}

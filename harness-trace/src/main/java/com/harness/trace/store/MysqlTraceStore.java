package com.harness.trace.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.harness.core.model.AgentTrace;
import com.harness.core.model.PageResponse;
import com.harness.core.model.TraceCursor;
import com.harness.core.env.MysqlConnectionPool;
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
 * MySQL-based trace store.
 * Uses shared HikariCP connection pool.
 */
public class MysqlTraceStore implements TraceStore {

    private static final Logger log = LoggerFactory.getLogger(MysqlTraceStore.class);
    private final ObjectMapper mapper;

    public MysqlTraceStore() {
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
    }

    @Override
    public void save(AgentTrace trace) {
        String sql = """
                INSERT INTO agent_traces
                (trace_id, timestamp, user_id, session_id, input_text, input_attachments,
                 intent, rag_hits, rerank_result,
                 llm_model, prompt_version, total_tokens,
                 steps_json, step_count,
                 final_output, risk_level, user_confirmed,
                 total_duration_ms, metadata, full_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    session_id = VALUES(session_id),
                    final_output = VALUES(final_output),
                    risk_level = VALUES(risk_level),
                    total_duration_ms = VALUES(total_duration_ms),
                    total_tokens = VALUES(total_tokens),
                    steps_json = VALUES(steps_json),
                    step_count = VALUES(step_count),
                    metadata = VALUES(metadata),
                    full_json = VALUES(full_json)
                """;

        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            String fullJson = mapper.writeValueAsString(trace);
            String stepsJson = mapper.writeValueAsString(trace.steps());
            String attachmentsJson = mapper.writeValueAsString(trace.inputAttachments());
            String ragHitsJson = mapper.writeValueAsString(trace.ragHits());
            String metadataJson = mapper.writeValueAsString(trace.metadata());

            ps.setString(1, trace.traceId());
            ps.setTimestamp(2, Timestamp.from(trace.timestamp()));
            ps.setString(3, trace.userId());
            ps.setString(4, trace.sessionId());
            ps.setString(5, trace.inputText());
            ps.setString(6, attachmentsJson);
            ps.setString(7, trace.intent());
            ps.setString(8, ragHitsJson);
            ps.setString(9, trace.rerankResult());
            ps.setString(10, trace.llmModel());
            ps.setString(11, trace.promptVersion());
            ps.setInt(12, trace.totalTokens());
            ps.setString(13, stepsJson);
            ps.setInt(14, trace.steps() != null ? trace.steps().size() : 0);
            ps.setString(15, trace.finalOutput());
            ps.setString(16, trace.riskLevel().name());
            ps.setBoolean(17, trace.userConfirmed());
            ps.setLong(18, trace.totalDurationMs());
            ps.setString(19, metadataJson);
            ps.setString(20, fullJson);

            ps.executeUpdate();
        } catch (SQLException | JsonProcessingException e) {
            log.error("Failed to save trace {} to MySQL: {}", trace.traceId(), e.getMessage(), e);
            throw new TraceStoreException("Failed to save trace " + trace.traceId(), e);
        }
    }

    @Override
    public Optional<AgentTrace> findById(String traceId) {
        String sql = "SELECT full_json FROM agent_traces WHERE trace_id = ?";
        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, traceId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(mapper.readValue(rs.getString("full_json"), AgentTrace.class));
            }
        } catch (Exception e) {
            log.error("Failed to find trace {} from MySQL: {}", traceId, e.getMessage(), e);
        }
        return Optional.empty();
    }

    @Override
    public List<AgentTrace> listRecent(int limit) {
        String sql = "SELECT full_json FROM agent_traces ORDER BY timestamp DESC LIMIT ?";
        List<AgentTrace> results = new ArrayList<>();
        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                results.add(mapper.readValue(rs.getString("full_json"), AgentTrace.class));
            }
        } catch (Exception e) {
            log.error("Failed to list traces from MySQL: {}", e.getMessage(), e);
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
        try (Connection connection = MysqlConnectionPool.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            if (cursor == null) {
                statement.setInt(2, limit + 1);
            } else {
                Timestamp timestamp = Timestamp.from(cursor.timestamp());
                statement.setTimestamp(2, timestamp);
                statement.setTimestamp(3, timestamp);
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
            connection = MysqlConnectionPool.getConnection();
            connection.setAutoCommit(false);
            int retained = 0;
            int deleted = 0;
            ExpiredTraceCursor cursor = null;
            boolean hasMore;
            do {
                List<ExpiredTraceCursor> fetched = findExpiredPage(
                        connection, Timestamp.from(cutoff), cursor, 500);
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
                        statement.setTimestamp(2, Timestamp.from(cutoff));
                        deleted += statement.executeUpdate();
                    }
                }
                cursor = page.isEmpty() ? cursor : page.getLast();
            } while (hasMore);
            connection.commit();
            return new CleanupResult(deleted, retained);
        } catch (SQLException | RuntimeException e) {
            rollback(connection);
            log.error("Failed to cleanup MySQL traces: {}", e.getMessage(), e);
            throw new TraceStoreException("Failed to cleanup MySQL traces", e);
        } finally {
            closeCleanupConnection(connection);
        }
    }

    private static List<ExpiredTraceCursor> findExpiredPage(
            Connection connection,
            Timestamp cutoff,
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
            statement.setTimestamp(1, cutoff);
            if (cursor == null) {
                statement.setInt(2, limit + 1);
            } else {
                statement.setTimestamp(2, cursor.timestamp());
                statement.setTimestamp(3, cursor.timestamp());
                statement.setString(4, cursor.traceId());
                statement.setInt(5, limit + 1);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    fetched.add(new ExpiredTraceCursor(
                            resultSet.getTimestamp("timestamp"),
                            resultSet.getString("trace_id")));
                }
            }
        }
        return fetched;
    }

    @Override
    public boolean deleteById(String traceId) {
        String sql = "DELETE FROM agent_traces WHERE trace_id = ?";
        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, traceId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete trace {} from MySQL: {}", traceId, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public int count() {
        String sql = "SELECT COUNT(*) FROM agent_traces";
        try (Connection conn = MysqlConnectionPool.getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            log.error("Failed to count MySQL traces: {}", e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public synchronized boolean updateMetadata(String traceId, Map<String, String> entries) {
        // Read-modify-write: load trace, merge metadata, save back (updates both metadata column and full_json)
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
        // Shared pool managed by MysqlConnectionPool.shutdown()
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
            connection.setAutoCommit(true);
            connection.close();
        } catch (SQLException e) {
            log.error("Failed to close trace cleanup connection: {}", e.getMessage(), e);
        }
    }

    private record ExpiredTraceCursor(Timestamp timestamp, String traceId) {
    }
}

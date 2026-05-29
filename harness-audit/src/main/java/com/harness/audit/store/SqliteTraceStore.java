package com.harness.audit.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.harness.core.model.AgentTrace;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite-based trace store. Default implementation.
 * Configured via HARNESS_AUDIT_* environment variables.
 */
public class SqliteTraceStore implements TraceStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteTraceStore.class);
    private final ObjectMapper mapper;
    private final String dbUrl;

    public SqliteTraceStore() {
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.dbUrl = EnvConfig.get().getString(EnvKey.AUDIT_DB_URL, "jdbc:sqlite:harness_trace.db");
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
    public int cleanup(int retentionDays) {
        String sql = "DELETE FROM agent_traces WHERE timestamp < ?";
        Instant cutoff = Instant.now().minusSeconds(retentionDays * 86400L);
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cutoff.toString());
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to cleanup traces: {}", e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public void close() {
        // SQLite connections are auto-closed
    }
}

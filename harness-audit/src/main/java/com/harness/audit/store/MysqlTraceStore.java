package com.harness.audit.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.harness.core.model.AgentTrace;
import com.harness.env.MysqlConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
    public int cleanup(int retentionDays) {
        String sql = "DELETE FROM agent_traces WHERE timestamp < ?";
        Instant cutoff = Instant.now().minusSeconds(retentionDays * 86400L);
        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(cutoff));
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to cleanup MySQL traces: {}", e.getMessage(), e);
            return 0;
        }
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
    public void close() {
        // Shared pool managed by MysqlConnectionPool.shutdown()
    }
}

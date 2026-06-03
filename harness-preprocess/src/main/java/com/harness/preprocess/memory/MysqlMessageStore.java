package com.harness.preprocess.memory;

import com.harness.core.model.MemoryMessage;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL-backed message store.
 * Reuses AUDIT_DB_URL/USER/PASS for connection (same database).
 */
public class MysqlMessageStore implements MessageStore {

    private static final Logger log = LoggerFactory.getLogger(MysqlMessageStore.class);
    private final String dbUrl;
    private final String dbUser;
    private final String dbPass;

    public MysqlMessageStore() {
        EnvConfig cfg = EnvConfig.get();
        this.dbUrl = cfg.getString(EnvKey.AUDIT_DB_URL, "jdbc:mysql://localhost:3306/agent");
        this.dbUser = cfg.getString(EnvKey.AUDIT_DB_USER, "root");
        this.dbPass = cfg.getString(EnvKey.AUDIT_DB_PASS, "1234");
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(dbUrl, dbUser, dbPass);
    }

    @Override
    public void save(String sessionId, String role, String content, boolean isSummary) {
        String sql = "INSERT INTO messages (session_id, role, content, is_summary) VALUES (?, ?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setString(2, role);
            ps.setString(3, content);
            ps.setBoolean(4, isSummary);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save message for session {}: {}", sessionId, e.getMessage(), e);
        }
    }

    @Override
    public List<MemoryMessage> loadForContext(String sessionId) {
        // Find the latest summary row id, then load that summary + all messages after it
        String latestSummarySql = "SELECT MAX(id) FROM messages WHERE session_id = ? AND is_summary = 1";
        List<MemoryMessage> messages = new ArrayList<>();
        try (Connection conn = getConnection()) {
            Long latestSummaryId = null;
            try (PreparedStatement ps = conn.prepareStatement(latestSummarySql)) {
                ps.setString(1, sessionId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    latestSummaryId = rs.getLong(1);
                    if (rs.wasNull()) latestSummaryId = null;
                }
            }

            String loadSql;
            if (latestSummaryId != null) {
                // Load summary row + all messages after it
                loadSql = "SELECT id, session_id, role, content, is_summary, created_at FROM messages WHERE session_id = ? AND id >= ? ORDER BY id ASC";
            } else {
                // No summary, load all messages
                loadSql = "SELECT id, session_id, role, content, is_summary, created_at FROM messages WHERE session_id = ? ORDER BY id ASC";
            }

            try (PreparedStatement ps = conn.prepareStatement(loadSql)) {
                ps.setString(1, sessionId);
                if (latestSummaryId != null) {
                    ps.setLong(2, latestSummaryId);
                }
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    messages.add(mapMessage(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load messages for session {}: {}", sessionId, e.getMessage(), e);
        }
        return messages;
    }

    @Override
    public int countUserMessages(String sessionId) {
        String sql = "SELECT COUNT(*) FROM messages WHERE session_id = ? AND role = 'user' AND is_summary = 0";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            log.error("Failed to count user messages for session {}: {}", sessionId, e.getMessage(), e);
        }
        return 0;
    }

    @Override
    public int sumUserContentLength(String sessionId) {
        String sql = "SELECT COALESCE(SUM(CHAR_LENGTH(content)), 0) FROM messages WHERE session_id = ? AND role = 'user' AND is_summary = 0";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            log.error("Failed to sum user content length for session {}: {}", sessionId, e.getMessage(), e);
        }
        return 0;
    }

    @Override
    public int countConversationTurns(String sessionId) {
        // A "turn" is a user message followed by an assistant message.
        // Count the number of user messages that have a subsequent assistant message.
        String sql = "SELECT COUNT(*) FROM messages WHERE session_id = ? AND role = 'user' AND is_summary = 0";
        int userCount = 0;
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) userCount = rs.getInt(1);
        } catch (SQLException e) {
            log.error("Failed to count conversation turns for session {}: {}", sessionId, e.getMessage(), e);
        }

        String assistantSql = "SELECT COUNT(*) FROM messages WHERE session_id = ? AND role = 'assistant' AND is_summary = 0";
        int assistantCount = 0;
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(assistantSql)) {
            ps.setString(1, sessionId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) assistantCount = rs.getInt(1);
        } catch (SQLException e) {
            log.error("Failed to count assistant messages for session {}: {}", sessionId, e.getMessage(), e);
        }

        return Math.min(userCount, assistantCount);
    }

    @Override
    public int countToolMessages(String sessionId) {
        String sql = "SELECT COUNT(*) FROM messages WHERE session_id = ? AND role LIKE '%tool%' AND is_summary = 0";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            log.error("Failed to count tool messages for session {}: {}", sessionId, e.getMessage(), e);
        }
        return 0;
    }

    @Override
    public int avgAssistantReplyLength(String sessionId) {
        String sql = "SELECT COALESCE(AVG(CHAR_LENGTH(content)), 0) FROM messages WHERE session_id = ? AND role = 'assistant' AND is_summary = 0";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            log.error("Failed to calculate avg assistant reply length for session {}: {}", sessionId, e.getMessage(), e);
        }
        return 0;
    }

    @Override
    public boolean hasUserQuestions(String sessionId) {
        String sql = "SELECT COUNT(*) FROM messages WHERE session_id = ? AND role = 'user' AND is_summary = 0 " +
                "AND (content LIKE '%?%' OR content LIKE '%？%' OR content LIKE '%how %' OR content LIKE '%what %' " +
                "OR content LIKE '%why %' OR content LIKE '%when %' OR content LIKE '%where %' OR content LIKE '%who %' " +
                "OR content LIKE '%which %' OR content LIKE '%can you%' OR content LIKE '%could you%' " +
                "OR content LIKE '%please %' OR content LIKE '%i want%' OR content LIKE '%i need%' " +
                "OR content LIKE '%help me%' OR content LIKE '%tell me%')";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1) > 0;
        } catch (SQLException e) {
            log.error("Failed to check user questions for session {}: {}", sessionId, e.getMessage(), e);
        }
        return false;
    }

    @Override
    public List<MemoryMessage> loadPage(String sessionId, long cursor, int limit, boolean ascending) {
        String direction = ascending ? "ASC" : "DESC";
        String operator = ascending ? ">" : "<";
        String sql;
        List<MemoryMessage> messages = new ArrayList<>();

        if (cursor > 0) {
            sql = "SELECT id, session_id, role, content, is_summary, created_at FROM messages " +
                    "WHERE session_id = ? AND id " + operator + " ? ORDER BY id " + direction + " LIMIT ?";
        } else {
            sql = "SELECT id, session_id, role, content, is_summary, created_at FROM messages " +
                    "WHERE session_id = ? ORDER BY id " + direction + " LIMIT ?";
        }

        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            if (cursor > 0) {
                ps.setLong(2, cursor);
                ps.setInt(3, Math.min(limit, 200));
            } else {
                ps.setInt(2, Math.min(limit, 200));
            }
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                messages.add(mapMessage(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to load message page for session {}: {}", sessionId, e.getMessage(), e);
        }
        // Return in chronological order (asc) regardless of query direction
        if (!ascending) {
            java.util.Collections.reverse(messages);
        }
        return messages;
    }

    @Override
    public int countByRole(String sessionId, String role) {
        String sql = "SELECT COUNT(*) FROM messages WHERE session_id = ? AND role = ? AND is_summary = 0";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setString(2, role);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            log.error("Failed to count {} messages for session {}: {}", role, sessionId, e.getMessage(), e);
        }
        return 0;
    }

    private MemoryMessage mapMessage(ResultSet rs) throws SQLException {
        return new MemoryMessage(
                rs.getLong("id"),
                rs.getString("session_id"),
                rs.getString("role"),
                rs.getString("content"),
                rs.getBoolean("is_summary"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}

package com.harness.input.memory;

import com.harness.core.model.MemoryMessage;
import com.harness.core.model.MessageBlock;
import com.harness.core.env.MysqlConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL-backed message store.
 * Uses shared HikariCP connection pool.
 * Content is stored as JSON (structured MessageBlock array).
 */
public class MysqlMessageStore implements MessageStore {

    private static final Logger log = LoggerFactory.getLogger(MysqlMessageStore.class);

    private Connection getConnection() throws SQLException {
        return MysqlConnectionPool.getConnection();
    }

    @Override
    public void save(String sessionId, String role, List<MessageBlock> content, boolean isSummary) {
        String sql = "INSERT INTO messages (session_id, role, content, is_summary) VALUES (?, ?, CAST(? AS JSON), ?)";
        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setString(2, role);
            ps.setString(3, MessageBlock.toJson(content));
            ps.setBoolean(4, isSummary);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MemoryStoreException("Failed to save message for session " + sessionId, e);
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
                loadSql = "SELECT id, session_id, role, content, is_summary, created_at FROM messages WHERE session_id = ? AND id >= ? ORDER BY id ASC";
            } else {
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
            throw new MemoryStoreException("Failed to load messages for session " + sessionId, e);
        }
        return messages;
    }

    @Override
    public int countUserMessages(String sessionId) {
        String sql = "SELECT COUNT(*) FROM messages WHERE session_id = ? AND role = 'user' AND is_summary = 0";
        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
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
        // Extract text from TEXT blocks using JSON functions
        String sql = "SELECT COALESCE(SUM(CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(c.val, '$.text')))), 0) " +
                "FROM messages, JSON_TABLE(content, '$[*]' COLUMNS(val JSON PATH '$') ) c " +
                "WHERE session_id = ? AND role = 'user' AND is_summary = 0 AND JSON_EXTRACT(c.val, '$.type') = 'TEXT'";
        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
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
        String sql = "SELECT COUNT(*) FROM messages WHERE session_id = ? AND role = 'user' AND is_summary = 0";
        int userCount = 0;
        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
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
        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
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
        // Average length of text extracted from TEXT blocks
        String sql = "SELECT COALESCE(AVG(CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(c.val, '$.text')))), 0) " +
                "FROM messages, JSON_TABLE(content, '$[*]' COLUMNS(val JSON PATH '$') ) c " +
                "WHERE session_id = ? AND role = 'assistant' AND is_summary = 0 AND JSON_EXTRACT(c.val, '$.type') = 'TEXT'";
        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
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
        // Search within TEXT block text values
        String sql = "SELECT COUNT(*) FROM messages m, JSON_TABLE(m.content, '$[*]' COLUMNS(val JSON PATH '$') ) c " +
                "WHERE m.session_id = ? AND m.role = 'user' AND m.is_summary = 0 " +
                "AND JSON_EXTRACT(c.val, '$.type') = 'TEXT' " +
                "AND (JSON_UNQUOTE(JSON_EXTRACT(c.val, '$.text')) LIKE '%?%' OR JSON_UNQUOTE(JSON_EXTRACT(c.val, '$.text')) LIKE '%？%')";
        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
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

        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
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
            throw new MemoryStoreException("Failed to load message page for session " + sessionId, e);
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
        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setString(2, role);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            log.error("Failed to count {} messages for session {}: {}", role, sessionId, e.getMessage(), e);
        }
        return 0;
    }

    @Override
    public SessionStats loadSessionStats(String sessionId) {
        String sql = """
                SELECT
                    SUM(CASE WHEN role = 'user' AND is_summary = 0 THEN 1 ELSE 0 END) AS user_msg_count,
                    COALESCE(SUM(CASE WHEN role = 'user' AND is_summary = 0 THEN user_chars ELSE 0 END), 0) AS user_char_count,
                    COALESCE(MIN(CASE WHEN role = 'user' AND is_summary = 0 THEN uc.cnt END), 0) AS conversation_turns,
                    SUM(CASE WHEN role LIKE '%tool%' AND is_summary = 0 THEN 1 ELSE 0 END) AS tool_msg_count,
                    COALESCE(AVG(CASE WHEN role = 'assistant' AND is_summary = 0 THEN asst_chars END), 0) AS avg_reply_len,
                    MAX(CASE WHEN role = 'user' AND is_summary = 0 AND has_q THEN 1 ELSE 0 END) AS has_questions
                FROM (
                    SELECT m.role, m.is_summary,
                        COALESCE((SELECT SUM(CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(c.val, '$.text'))))
                            FROM JSON_TABLE(m.content, '$[*]' COLUMNS(val JSON PATH '$') ) c
                            WHERE JSON_EXTRACT(c.val, '$.type') = 'TEXT'), 0) AS user_chars,
                        COALESCE((SELECT SUM(CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(c.val, '$.text'))))
                            FROM JSON_TABLE(m.content, '$[*]' COLUMNS(val JSON PATH '$') ) c
                            WHERE JSON_EXTRACT(c.val, '$.type') = 'TEXT'), 0) AS asst_chars,
                        EXISTS (SELECT 1 FROM JSON_TABLE(m.content, '$[*]' COLUMNS(val JSON PATH '$') ) c
                            WHERE JSON_EXTRACT(c.val, '$.type') = 'TEXT'
                            AND (JSON_UNQUOTE(JSON_EXTRACT(c.val, '$.text')) LIKE '%?%'
                                OR JSON_UNQUOTE(JSON_EXTRACT(c.val, '$.text')) LIKE '%？%')) AS has_q
                    FROM messages m
                    WHERE m.session_id = ? AND m.is_summary = 0
                ) sub,
                LATERAL (SELECT COUNT(*) AS cnt FROM messages WHERE session_id = ? AND role = 'user' AND is_summary = 0) uc
                """;
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setString(2, sessionId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new SessionStats(
                        rs.getInt("user_msg_count"),
                        rs.getInt("user_char_count"),
                        rs.getInt("conversation_turns"),
                        rs.getInt("tool_msg_count"),
                        rs.getInt("avg_reply_len"),
                        rs.getInt("has_questions") > 0
                );
            }
        } catch (SQLException e) {
            log.error("Failed to load session stats for {}: {}", sessionId, e.getMessage(), e);
        }
        return new SessionStats(0, 0, 0, 0, 0, false);
    }

    @Override
    public int deleteBySession(String sessionId) {
        String sql = "DELETE FROM messages WHERE session_id = ?";
        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            int deleted = ps.executeUpdate();
            log.debug("Deleted {} messages for session {}", deleted, sessionId);
            return deleted;
        } catch (SQLException e) {
            log.error("Failed to delete messages for session {}: {}", sessionId, e.getMessage(), e);
            return 0;
        }
    }

    private MemoryMessage mapMessage(ResultSet rs) throws SQLException {
        String contentJson = rs.getString("content");
        List<MessageBlock> blocks = MessageBlock.fromJson(contentJson);
        if (blocks == null) blocks = List.of(new MessageBlock(MessageBlock.BlockType.TEXT, contentJson, null));
        return new MemoryMessage(
                rs.getLong("id"),
                rs.getString("session_id"),
                rs.getString("role"),
                blocks,
                rs.getBoolean("is_summary"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}

package com.harness.input.memory;

import com.harness.core.model.MemoryMessage;
import com.harness.core.model.MessageBlock;
import com.harness.core.env.MysqlConnectionPool;
import com.harness.core.persistence.SqlConnectionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MySQL-backed message store.
 * Uses shared HikariCP connection pool.
 * Content is stored as JSON (structured MessageBlock array).
 */
public class MysqlMessageStore implements TurnCompressibleMessageStore {

    private static final Logger log = LoggerFactory.getLogger(MysqlMessageStore.class);
    private final SqlConnectionProvider connectionProvider;

    public MysqlMessageStore() {
        this(MysqlConnectionPool::getConnection);
    }

    public MysqlMessageStore(SqlConnectionProvider connectionProvider) {
        this.connectionProvider = java.util.Objects.requireNonNull(
                connectionProvider, "connectionProvider");
    }

    private Connection getConnection() throws SQLException {
        return connectionProvider.getConnection();
    }

    @Override
    public long save(MessageWrite message) {
        return saveBatch(List.of(message)).getFirst();
    }

    @Override
    public List<Long> saveBatch(List<MessageWrite> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        String sql = """
                INSERT INTO messages (session_id, trace_id, role, content, is_summary)
                VALUES (?, ?, ?, CAST(? AS JSON), ?)
                """;
        Connection connection = null;
        try {
            connection = getConnection();
            connection.setAutoCommit(false);
            List<Long> ids = new ArrayList<>(messages.size());
            try (PreparedStatement statement = connection.prepareStatement(
                    sql, Statement.RETURN_GENERATED_KEYS)) {
                for (MessageWrite message : messages) {
                    statement.setString(1, message.sessionId());
                    statement.setString(2, message.traceId());
                    statement.setString(3, message.role());
                    statement.setString(4, MessageBlock.toJson(message.content()));
                    statement.setBoolean(5, message.isSummary());
                    statement.addBatch();
                }
                int[] updates = statement.executeBatch();
                if (updates.length != messages.size()) {
                    throw new SQLException("Message batch update count mismatch");
                }
                try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                    while (generatedKeys.next()) {
                        ids.add(generatedKeys.getLong(1));
                    }
                }
            }
            if (ids.size() != messages.size()) {
                throw new SQLException("Message batch generated key count mismatch");
            }
            connection.commit();
            return List.copyOf(ids);
        } catch (SQLException e) {
            rollback(connection);
            throw new MemoryStoreException("Failed to save message batch", e);
        } finally {
            close(connection);
        }
    }

    @Override
    public List<MemoryMessage> loadForContext(String sessionId) {
        String loadSql = "SELECT id, session_id, trace_id, role, content, is_summary, created_at "
                + "FROM messages WHERE session_id = ? ORDER BY id ASC";
        List<MemoryMessage> messages = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(loadSql)) {
            ps.setString(1, sessionId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                messages.add(mapMessage(rs));
            }
        } catch (SQLException e) {
            throw new MemoryStoreException("Failed to load messages for session " + sessionId, e);
        }
        long legacyAnchor = messages.stream()
                .filter(MemoryMessage::isSummary)
                .filter(message -> !isTurnSummary(message))
                .mapToLong(MemoryMessage::id)
                .max()
                .orElse(0);
        return legacyAnchor == 0
                ? List.copyOf(messages)
                : messages.stream().filter(message -> message.id() >= legacyAnchor).toList();
    }

    @Override
    public void replaceTurnWithSummary(
            String sessionId,
            List<Long> messageIds,
            MessageWrite summary
    ) {
        if (messageIds == null || messageIds.isEmpty()) {
            throw new IllegalArgumentException("messageIds must not be empty");
        }
        if (!sessionId.equals(summary.sessionId()) || !summary.isSummary()) {
            throw new IllegalArgumentException(
                    "Turn summary must belong to the session and be marked as summary");
        }
        List<Long> ids = messageIds.stream().distinct().sorted().toList();
        if (ids.getFirst() <= 0) {
            throw new IllegalArgumentException("Turn message ids must be persisted ids");
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String lockSql = "SELECT id FROM messages WHERE session_id = ? AND id IN ("
                + placeholders + ") FOR UPDATE";
        String updateSql = "UPDATE messages SET trace_id = ?, role = ?, content = CAST(? AS JSON), "
                + "is_summary = 1 WHERE session_id = ? AND id = ?";
        String deleteSql = ids.size() == 1 ? null
                : "DELETE FROM messages WHERE session_id = ? AND id IN ("
                        + String.join(",", java.util.Collections.nCopies(ids.size() - 1, "?"))
                        + ")";
        Connection connection = null;
        try {
            connection = getConnection();
            connection.setAutoCommit(false);
            int found = 0;
            try (PreparedStatement statement = connection.prepareStatement(lockSql)) {
                statement.setString(1, sessionId);
                for (int i = 0; i < ids.size(); i++) {
                    statement.setLong(i + 2, ids.get(i));
                }
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) found++;
                }
            }
            if (found != ids.size()) {
                throw new SQLException("Completed Turn changed before compression");
            }
            try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
                statement.setString(1, summary.traceId());
                statement.setString(2, summary.role());
                statement.setString(3, MessageBlock.toJson(summary.content()));
                statement.setString(4, sessionId);
                statement.setLong(5, ids.getFirst());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Turn summary update count mismatch");
                }
            }
            if (deleteSql != null) {
                try (PreparedStatement statement = connection.prepareStatement(deleteSql)) {
                    statement.setString(1, sessionId);
                    for (int i = 1; i < ids.size(); i++) {
                        statement.setLong(i + 1, ids.get(i));
                    }
                    if (statement.executeUpdate() != ids.size() - 1) {
                        throw new SQLException("Turn message delete count mismatch");
                    }
                }
            }
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            rollback(connection);
            throw new MemoryStoreException(
                    "Failed to replace completed Turn in session " + sessionId, e);
        } finally {
            close(connection);
        }
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
        // Extract text from TEXT blocks using JSON functions
        String sql = "SELECT COALESCE(SUM(CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(c.val, '$.text')))), 0) " +
                "FROM messages, JSON_TABLE(content, '$[*]' COLUMNS(val JSON PATH '$') ) c " +
                "WHERE session_id = ? AND role = 'user' AND is_summary = 0 AND JSON_EXTRACT(c.val, '$.type') = 'TEXT'";
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
        return findConversationBoundary(sessionId).completeTurns();
    }

    @Override
    public ConversationBoundary findConversationBoundary(String sessionId) {
        String sql = """
                WITH ordered AS (
                    SELECT id, role,
                           SUM(CASE WHEN role = 'user' THEN 1 ELSE 0 END)
                               OVER (ORDER BY id) AS turn_group
                    FROM messages
                    WHERE session_id = ? AND is_summary = 0
                      AND role IN ('user', 'assistant')
                ), completed AS (
                    SELECT turn_group,
                           MIN(CASE WHEN role = 'assistant' THEN id END) AS closing_message_id
                    FROM ordered
                    WHERE turn_group > 0
                    GROUP BY turn_group
                    HAVING SUM(CASE WHEN role = 'user' THEN 1 ELSE 0 END) > 0
                       AND SUM(CASE WHEN role = 'assistant' THEN 1 ELSE 0 END) > 0
                )
                SELECT COUNT(*) AS complete_turns,
                       COALESCE(MAX(closing_message_id), 0) AS latest_complete_turn_message_id
                FROM completed
                """;
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Conversation boundary query returned no row");
                }
                return new ConversationBoundary(
                        resultSet.getInt("complete_turns"),
                        resultSet.getLong("latest_complete_turn_message_id"));
            }
        } catch (SQLException e) {
            throw new MemoryStoreException(
                    "Failed to find conversation boundary for session " + sessionId, e);
        }
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
        // Average length of text extracted from TEXT blocks
        String sql = "SELECT COALESCE(AVG(CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(c.val, '$.text')))), 0) " +
                "FROM messages, JSON_TABLE(content, '$[*]' COLUMNS(val JSON PATH '$') ) c " +
                "WHERE session_id = ? AND role = 'assistant' AND is_summary = 0 AND JSON_EXTRACT(c.val, '$.type') = 'TEXT'";
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
        // Search within TEXT block text values
        String sql = "SELECT COUNT(*) FROM messages m, JSON_TABLE(m.content, '$[*]' COLUMNS(val JSON PATH '$') ) c " +
                "WHERE m.session_id = ? AND m.role = 'user' AND m.is_summary = 0 " +
                "AND JSON_EXTRACT(c.val, '$.type') = 'TEXT' " +
                "AND (JSON_UNQUOTE(JSON_EXTRACT(c.val, '$.text')) LIKE '%?%' OR JSON_UNQUOTE(JSON_EXTRACT(c.val, '$.text')) LIKE '%？%')";
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
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        String direction = ascending ? "ASC" : "DESC";
        String operator = ascending ? ">" : "<";
        String sql;
        List<MemoryMessage> messages = new ArrayList<>();

        if (cursor > 0) {
            sql = "SELECT id, session_id, trace_id, role, content, is_summary, created_at FROM messages " +
                    "WHERE session_id = ? AND role IN ('user', 'assistant') AND id "
                    + operator + " ? ORDER BY id " + direction + " LIMIT ?";
        } else {
            sql = "SELECT id, session_id, trace_id, role, content, is_summary, created_at FROM messages " +
                    "WHERE session_id = ? AND role IN ('user', 'assistant') "
                    + "ORDER BY id " + direction + " LIMIT ?";
        }

        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            if (cursor > 0) {
                ps.setLong(2, cursor);
                ps.setInt(3, limit);
            } else {
                ps.setInt(2, limit);
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
    public Optional<MemoryMessage> findByIdAndSession(long messageId, String sessionId) {
        if (messageId <= 0) {
            throw new IllegalArgumentException("messageId must be positive");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        String sql = """
                SELECT id, session_id, trace_id, role, content, is_summary, created_at
                FROM messages
                WHERE id = ? AND session_id = ?
                """;
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, messageId);
            statement.setString(2, sessionId.trim());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(mapMessage(resultSet))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new MemoryStoreException(
                    "Failed to find message " + messageId + " in session " + sessionId, e);
        }
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

    @Override
    public SessionStats loadSessionStats(String sessionId) {
        String sql = """
                SELECT
                    SUM(CASE WHEN role = 'user' AND is_summary = 0 THEN 1 ELSE 0 END) AS user_msg_count,
                    COALESCE(SUM(CASE WHEN role = 'user' AND is_summary = 0 THEN user_chars ELSE 0 END), 0) AS user_char_count,
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
                ) sub
                """;
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                ConversationBoundary boundary = findConversationBoundary(sessionId);
                return new SessionStats(
                        rs.getInt("user_msg_count"),
                        rs.getInt("user_char_count"),
                        boundary.completeTurns(),
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

    private MemoryMessage mapMessage(ResultSet rs) throws SQLException {
        String contentJson = rs.getString("content");
        List<MessageBlock> blocks = MessageBlock.fromJson(contentJson);
        if (blocks == null) blocks = List.of(new MessageBlock(MessageBlock.BlockType.TEXT, contentJson, null));
        return new MemoryMessage(
                rs.getLong("id"),
                rs.getString("session_id"),
                rs.getString("trace_id"),
                rs.getString("role"),
                blocks,
                rs.getBoolean("is_summary"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private static boolean isTurnSummary(MemoryMessage message) {
        return message.content().stream()
                .map(MessageBlock::metadata)
                .filter(java.util.Objects::nonNull)
                .anyMatch(metadata -> "turn".equals(metadata.get("summaryType")));
    }

    private static void rollback(Connection connection) {
        if (connection != null) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // Preserve original failure.
            }
        }
    }

    private static void close(Connection connection) {
        if (connection != null) {
            try {
                connection.setAutoCommit(true);
                connection.close();
            } catch (SQLException ignored) {
                // Preserve original failure.
            }
        }
    }
}

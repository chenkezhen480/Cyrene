package com.harness.preprocess.memory;

import com.harness.core.model.Preference;
import com.harness.env.MysqlConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL-backed preference store.
 * Uses shared HikariCP connection pool.
 */
public class MysqlPreferenceStore implements PreferenceStore {

    private static final Logger log = LoggerFactory.getLogger(MysqlPreferenceStore.class);

    private Connection getConnection() throws SQLException {
        return MysqlConnectionPool.getConnection();
    }

    @Override
    public List<Preference> loadByUser(String userId) {
        String sql = "SELECT id, user_id, category, content, source_session_id, created_at, updated_at FROM user_preferences WHERE user_id = ? ORDER BY category";
        List<Preference> prefs = new ArrayList<>();
        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                prefs.add(mapPreference(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to load preferences for user {}: {}", userId, e.getMessage(), e);
        }
        return prefs;
    }

    @Override
    public void upsert(String userId, String category, String content, String sourceSessionId) {
        String sql = """
                INSERT INTO user_preferences (user_id, category, content, source_session_id)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    content = VALUES(content),
                    source_session_id = VALUES(source_session_id),
                    updated_at = CURRENT_TIMESTAMP(3)
                """;
        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, category);
            ps.setString(3, content);
            ps.setString(4, sourceSessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to upsert preference for user {} category {}: {}", userId, category, e.getMessage(), e);
        }
    }

    private Preference mapPreference(ResultSet rs) throws SQLException {
        return new Preference(
                rs.getLong("id"),
                rs.getString("user_id"),
                rs.getString("category"),
                rs.getString("content"),
                rs.getString("source_session_id"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }
}

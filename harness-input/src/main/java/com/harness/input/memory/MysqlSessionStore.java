package com.harness.input.memory;

import com.harness.core.model.Session;
import com.harness.core.env.MysqlConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * MySQL-backed session store.
 * Uses shared HikariCP connection pool.
 */
public class MysqlSessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(MysqlSessionStore.class);

    @Override
    public Session create(String userId) {
        String id = UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        String sql = "INSERT INTO sessions (id, user_id, created_at, last_active, status) VALUES (?, ?, ?, ?, 'active')";
        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, userId);
            ps.setTimestamp(3, Timestamp.from(now));
            ps.setTimestamp(4, Timestamp.from(now));
            ps.executeUpdate();
            log.debug("Created session {} for user {}", id, userId);
            return new Session(id, userId, null, now, now, null, Session.SessionStatus.active);
        } catch (SQLException e) {
            log.error("Failed to create session: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create session", e);
        }
    }

    @Override
    public Optional<Session> findActive(String sessionId) {
        String sql = "SELECT id, user_id, title, created_at, last_active, ended_at, status FROM sessions WHERE id = ? AND status = 'active'";
        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(mapSession(rs));
            }
        } catch (SQLException e) {
            throw new MemoryStoreException("Failed to find active session " + sessionId, e);
        }
        return Optional.empty();
    }

    @Override
    public List<Session> findActiveByUser(String userId) {
        String sql = "SELECT id, user_id, title, created_at, last_active, ended_at, status FROM sessions WHERE user_id = ? AND status = 'active'";
        List<Session> sessions = new ArrayList<>();
        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                sessions.add(mapSession(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to find active sessions for user {}: {}", userId, e.getMessage(), e);
        }
        return sessions;
    }

    @Override
    public List<Session> findTimedOut(Duration timeout) {
        String sql = "SELECT id, user_id, title, created_at, last_active, ended_at, status FROM sessions WHERE status = 'active' AND last_active < ?";
        Instant cutoff = Instant.now().minus(timeout);
        List<Session> sessions = new ArrayList<>();
        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(cutoff));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                sessions.add(mapSession(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to find timed-out sessions: {}", e.getMessage(), e);
        }
        return sessions;
    }

    @Override
    public void close(String sessionId, Session.SessionStatus status) {
        String sql = "UPDATE sessions SET status = ?, ended_at = ? WHERE id = ?";
        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setString(3, sessionId);
            ps.executeUpdate();
            log.debug("Closed session {} with status {}", sessionId, status);
        } catch (SQLException e) {
            log.error("Failed to close session {}: {}", sessionId, e.getMessage(), e);
        }
    }

    @Override
    public void updateLastActive(String sessionId) {
        String sql = "UPDATE sessions SET last_active = ?, status = 'active' WHERE id = ?";
        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setString(2, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to update last_active for session {}: {}", sessionId, e.getMessage(), e);
        }
    }

    @Override
    public void markRefinementStatus(String sessionId, String status) {
        String sql = "UPDATE sessions SET refinement_status = ? WHERE id = ?";
        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, sessionId);
            ps.executeUpdate();
            log.debug("Marked session {} refinement_status={}", sessionId, status);
        } catch (SQLException e) {
            log.error("Failed to mark refinement status for session {}: {}", sessionId, e.getMessage(), e);
        }
    }

    @Override
    public boolean claimForRefinement(String sessionId) {
        String sql = "UPDATE sessions SET refinement_status = 'in_progress' WHERE id = ? AND refinement_status = 'pending'";
        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            int updated = ps.executeUpdate();
            if (updated > 0) {
                log.debug("Claimed session {} for refinement", sessionId);
                return true;
            }
            log.debug("Session {} not claimed (not in 'pending' state)", sessionId);
        } catch (SQLException e) {
            log.error("Failed to claim session {} for refinement: {}", sessionId, e.getMessage(), e);
        }
        return false;
    }

    @Override
    public List<Session> findStuckRefinements(Duration stuckThreshold) {
        String sql = "SELECT id, user_id, title, created_at, last_active, ended_at, status FROM sessions " +
                "WHERE refinement_status = 'in_progress' AND last_active < ?";
        Instant cutoff = Instant.now().minus(stuckThreshold);
        List<Session> sessions = new ArrayList<>();
        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(cutoff));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                sessions.add(mapSession(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to find stuck refinements: {}", e.getMessage(), e);
        }
        return sessions;
    }

    @Override
    public void resetRefinementToPending(String sessionId) {
        String sql = "UPDATE sessions SET refinement_status = 'pending' WHERE id = ? AND refinement_status = 'in_progress'";
        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            int updated = ps.executeUpdate();
            if (updated > 0) {
                log.debug("Reset refinement_status to 'pending' for session {}", sessionId);
            }
        } catch (SQLException e) {
            log.error("Failed to reset refinement status for session {}: {}", sessionId, e.getMessage(), e);
        }
    }

    @Override
    public Optional<Session> findById(String sessionId) {
        String sql = "SELECT id, user_id, title, created_at, last_active, ended_at, status FROM sessions WHERE id = ?";
        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(mapSession(rs));
            }
        } catch (SQLException e) {
            throw new MemoryStoreException("Failed to find session " + sessionId, e);
        }
        return Optional.empty();
    }

    @Override
    public List<Session> findAll(String userId, Session.SessionStatus status, Instant cursor, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, user_id, title, created_at, last_active, ended_at, status FROM sessions WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (userId != null) {
            sql.append(" AND user_id = ?");
            params.add(userId);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            params.add(status.name());
        }
        if (cursor != null) {
            sql.append(" AND last_active < ?");
            params.add(Timestamp.from(cursor));
        }
        sql.append(" ORDER BY last_active DESC LIMIT ?");
        params.add(limit);

        List<Session> sessions = new ArrayList<>();
        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                if (p instanceof String s) ps.setString(i + 1, s);
                else if (p instanceof Timestamp t) ps.setTimestamp(i + 1, t);
                else if (p instanceof Integer n) ps.setInt(i + 1, n);
            }
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                sessions.add(mapSession(rs));
            }
        } catch (SQLException e) {
            throw new MemoryStoreException("Failed to list sessions", e);
        }
        return sessions;
    }

    @Override
    public void updateTitle(String sessionId, String title) {
        String sql = "UPDATE sessions SET title = ? WHERE id = ?";
        try (Connection conn = MysqlConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, title);
            ps.setString(2, sessionId);
            ps.executeUpdate();
            log.debug("Updated title for session {}", sessionId);
        } catch (SQLException e) {
            log.error("Failed to update title for session {}: {}", sessionId, e.getMessage(), e);
        }
    }

    private Session mapSession(ResultSet rs) throws SQLException {
        Timestamp endedTs = rs.getTimestamp("ended_at");
        return new Session(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("title"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("last_active").toInstant(),
                endedTs != null ? endedTs.toInstant() : null,
                Session.SessionStatus.valueOf(rs.getString("status"))
        );
    }
}

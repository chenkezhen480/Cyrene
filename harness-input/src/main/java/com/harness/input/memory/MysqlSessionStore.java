package com.harness.input.memory;

import com.harness.core.env.MysqlConnectionPool;
import com.harness.core.model.PageResponse;
import com.harness.core.model.Session;
import com.harness.core.model.SessionCursor;
import com.harness.core.persistence.SqlConnectionProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** MySQL Session Store with null-safe owner checks and stable compound cursors. */
public class MysqlSessionStore implements SessionStore {

    private static final String COLUMNS = """
            id, user_id, tenant_id, title, created_at, last_active, ended_at, status
            """;

    private final SqlConnectionProvider connectionProvider;

    public MysqlSessionStore() {
        this(MysqlConnectionPool::getConnection);
    }

    public MysqlSessionStore(SqlConnectionProvider connectionProvider) {
        this.connectionProvider = java.util.Objects.requireNonNull(
                connectionProvider, "connectionProvider");
    }

    @Override
    public Session create(String userId, String tenantId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        String sql = """
                INSERT INTO sessions
                    (id, user_id, tenant_id, created_at, last_active, status)
                VALUES (?, ?, ?, ?, ?, 'active')
                """;
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.setString(2, userId.trim());
            statement.setString(3, normalizeTenant(tenantId));
            statement.setTimestamp(4, Timestamp.from(now));
            statement.setTimestamp(5, Timestamp.from(now));
            statement.executeUpdate();
            return new Session(
                    id, userId.trim(), normalizeTenant(tenantId), null, now, now, null,
                    Session.SessionStatus.active);
        } catch (SQLException e) {
            throw new MemoryStoreException("Failed to create Session", e);
        }
    }

    @Override
    public Optional<Session> findActiveByOwner(
            String sessionId,
            String userId,
            String tenantId
    ) {
        return findOwned(sessionId, userId, tenantId, true);
    }

    @Override
    public Optional<Session> findByIdAndOwner(
            String sessionId,
            String userId,
            String tenantId
    ) {
        return findOwned(sessionId, userId, tenantId, false);
    }

    @Override
    public Optional<Session> findByIdForInternalTask(String sessionId) {
        String sql = "SELECT " + COLUMNS + " FROM sessions WHERE id = ?";
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new MemoryStoreException("Failed to read internal Session " + sessionId, e);
        }
    }

    @Override
    public PageResponse<Session> findTimedOut(
            Duration timeout,
            SessionCursor cursor,
            int limit
    ) {
        return findTimedOutPage(null, null, false, timeout, cursor, limit);
    }

    @Override
    public PageResponse<Session> findTimedOutByOwner(
            String userId,
            String tenantId,
            Duration timeout,
            SessionCursor cursor,
            int limit
    ) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        return findTimedOutPage(userId, tenantId, true, timeout, cursor, limit);
    }

    @Override
    public PageResponse<Session> findAllByOwner(
            String userId,
            String tenantId,
            Session.SessionStatus status,
            SessionCursor cursor,
            int limit
    ) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        validateLimit(limit);
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append("""
                 FROM sessions WHERE user_id = ? AND tenant_id <=> ?
                """);
        if (status != null) {
            sql.append(" AND status = ?");
        }
        if (cursor != null) {
            sql.append(" AND (last_active < ? OR (last_active = ? AND id < ?))");
        }
        sql.append(" ORDER BY last_active DESC, id DESC LIMIT ?");
        List<Session> sessions = new ArrayList<>();
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int parameter = 1;
            statement.setString(parameter++, userId.trim());
            statement.setString(parameter++, normalizeTenant(tenantId));
            if (status != null) {
                statement.setString(parameter++, status.name());
            }
            if (cursor != null) {
                Timestamp timestamp = Timestamp.from(cursor.lastActive());
                statement.setTimestamp(parameter++, timestamp);
                statement.setTimestamp(parameter++, timestamp);
                statement.setString(parameter++, cursor.sessionId());
            }
            statement.setInt(parameter, limit + 1);
            readAll(statement, sessions);
        } catch (SQLException e) {
            throw new MemoryStoreException("Failed to list owned Sessions", e);
        }
        return page(sessions, limit);
    }

    @Override
    public void close(String sessionId, Session.SessionStatus status) {
        if (status == null || status == Session.SessionStatus.active) {
            throw new IllegalArgumentException("closed Session status is required");
        }
        executeRequiredUpdate(
                "UPDATE sessions SET status = ?, ended_at = ? WHERE id = ?",
                statement -> {
                    statement.setString(1, status.name());
                    statement.setTimestamp(2, Timestamp.from(Instant.now()));
                    statement.setString(3, sessionId);
                },
                "Session not found: " + sessionId);
    }

    @Override
    public void updateLastActive(String sessionId) {
        executeRequiredUpdate(
                "UPDATE sessions SET last_active = ?, status = 'active', ended_at = NULL WHERE id = ?",
                statement -> {
                    statement.setTimestamp(1, Timestamp.from(Instant.now()));
                    statement.setString(2, sessionId);
                },
                "Session not found: " + sessionId);
    }

    @Override
    public void updateTitle(String sessionId, String title) {
        executeRequiredUpdate(
                "UPDATE sessions SET title = ? WHERE id = ?",
                statement -> {
                    statement.setString(1, title);
                    statement.setString(2, sessionId);
                },
                "Session not found: " + sessionId);
    }

    private Optional<Session> findOwned(
            String sessionId,
            String userId,
            String tenantId,
            boolean activeOnly
    ) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        String sql = "SELECT " + COLUMNS
                + " FROM sessions WHERE id = ? AND user_id = ? AND tenant_id <=> ?"
                + (activeOnly ? " AND status = 'active'" : "");
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            statement.setString(2, userId.trim());
            statement.setString(3, normalizeTenant(tenantId));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new MemoryStoreException("Failed to read owned Session " + sessionId, e);
        }
    }

    private PageResponse<Session> findTimedOutPage(
            String userId,
            String tenantId,
            boolean ownerScoped,
            Duration timeout,
            SessionCursor cursor,
            int limit
    ) {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        validateLimit(limit);
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append("""
                 FROM sessions WHERE status = 'active' AND last_active < ?
                """);
        if (ownerScoped) {
            sql.append(" AND user_id = ? AND tenant_id <=> ?");
        }
        if (cursor != null) {
            sql.append(" AND (last_active > ? OR (last_active = ? AND id > ?))");
        }
        sql.append(" ORDER BY last_active, id LIMIT ?");
        List<Session> sessions = new ArrayList<>();
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int parameter = 1;
            statement.setTimestamp(parameter++, Timestamp.from(Instant.now().minus(timeout)));
            if (ownerScoped) {
                statement.setString(parameter++, userId.trim());
                statement.setString(parameter++, normalizeTenant(tenantId));
            }
            if (cursor != null) {
                Timestamp timestamp = Timestamp.from(cursor.lastActive());
                statement.setTimestamp(parameter++, timestamp);
                statement.setTimestamp(parameter++, timestamp);
                statement.setString(parameter++, cursor.sessionId());
            }
            statement.setInt(parameter, limit + 1);
            readAll(statement, sessions);
        } catch (SQLException e) {
            throw new MemoryStoreException("Failed to list timed-out Sessions", e);
        }
        return page(sessions, limit);
    }

    private void executeRequiredUpdate(
            String sql,
            SqlBinder binder,
            String missingMessage
    ) {
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            if (statement.executeUpdate() != 1) {
                throw new MemoryStoreException(missingMessage);
            }
        } catch (SQLException e) {
            throw new MemoryStoreException("Failed to update Session", e);
        }
    }

    private static void readAll(PreparedStatement statement, List<Session> sessions)
            throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                sessions.add(map(resultSet));
            }
        }
    }

    private static PageResponse<Session> page(List<Session> fetched, int limit) {
        return PageResponse.fromFetched(
                fetched,
                limit,
                session -> session.lastActive() + "|" + session.id());
    }

    private static Session map(ResultSet resultSet) throws SQLException {
        Timestamp endedAt = resultSet.getTimestamp("ended_at");
        return new Session(
                resultSet.getString("id"),
                resultSet.getString("user_id"),
                resultSet.getString("tenant_id"),
                resultSet.getString("title"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("last_active").toInstant(),
                endedAt == null ? null : endedAt.toInstant(),
                Session.SessionStatus.valueOf(resultSet.getString("status")));
    }

    private static String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? null : tenantId.trim();
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}

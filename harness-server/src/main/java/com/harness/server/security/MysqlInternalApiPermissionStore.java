package com.harness.server.security;

import com.harness.core.model.PageResponse;
import com.harness.core.persistence.SqlConnectionProvider;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Set;

/** No cache: revoked endpoint grants take effect on the next request. */
public class MysqlInternalApiPermissionStore {
    private final SqlConnectionProvider connections;

    public MysqlInternalApiPermissionStore(SqlConnectionProvider connections) {
        this.connections = java.util.Objects.requireNonNull(connections, "connections");
    }

    public boolean isAllowed(String tenantId, String identity, String endpointKey) {
        try (var connection = connections.getConnection();
             var statement = connection.prepareStatement("SELECT 1 FROM internal_api_permission "
                     + "WHERE tenant_id = ? AND identity = ? AND endpoint_key = ? LIMIT 1")) {
            statement.setString(1, tenantId);
            statement.setString(2, identity);
            statement.setString(3, endpointKey);
            try (var rows = statement.executeQuery()) { return rows.next(); }
        } catch (SQLException | RuntimeException e) {
            throw new PermissionStoreException("Internal API permission store is unavailable", e);
        }
    }

    public PageResponse<PermissionView> page(String tenantId, String identity, long cursor, int limit) {
        if (cursor < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid permission page");
        try (var connection = connections.getConnection();
             var statement = connection.prepareStatement("SELECT id, identity, endpoint_key FROM internal_api_permission "
                     + "WHERE tenant_id = ? " + (identity == null ? "" : "AND identity = ? ")
                     + "AND id > ? ORDER BY id LIMIT ?")) {
            statement.setString(1, tenantId);
            int parameter = 2;
            if (identity != null) statement.setString(parameter++, identity);
            statement.setLong(parameter++, cursor);
            statement.setInt(parameter, limit + 1);
            try (var rows = statement.executeQuery()) {
                var result = new ArrayList<PermissionView>();
                while (rows.next()) result.add(new PermissionView(rows.getLong("id"), tenantId,
                        rows.getString("identity"), rows.getString("endpoint_key")));
                return PageResponse.fromFetched(result, limit, row -> Long.toString(row.id()));
            }
        } catch (SQLException | RuntimeException e) {
            throw new PermissionStoreException("Failed to list internal API permissions", e);
        }
    }

    public void replace(String tenantId, String identity, Set<String> endpointKeys) {
        var keys = endpointKeys.stream().sorted().toList();
        try (Connection connection = connections.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var delete = connection.prepareStatement(
                        "DELETE FROM internal_api_permission WHERE tenant_id = ? AND identity = ?"
                                + (keys.isEmpty() ? "" : " AND endpoint_key NOT IN ("
                                + String.join(",", java.util.Collections.nCopies(keys.size(), "?")) + ")"))) {
                    delete.setString(1, tenantId);
                    delete.setString(2, identity);
                    for (int i = 0; i < keys.size(); i++) delete.setString(i + 3, keys.get(i));
                    delete.executeUpdate();
                }
                try (var insert = connection.prepareStatement("INSERT INTO internal_api_permission "
                        + "(tenant_id, identity, endpoint_key) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE endpoint_key = VALUES(endpoint_key)")) {
                    for (String key : keys) {
                        insert.setString(1, tenantId);
                        insert.setString(2, identity);
                        insert.setString(3, key);
                        insert.addBatch();
                    }
                    if (!endpointKeys.isEmpty()) insert.executeBatch();
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                try { connection.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            }
        } catch (SQLException | RuntimeException e) {
            throw new PermissionStoreException("Failed to replace internal API permissions", e);
        }
    }

    public record PermissionView(long id, String tenantId, String identity, String endpointKey) {}

    public static final class PermissionStoreException extends RuntimeException {
        public PermissionStoreException(String message, Throwable cause) { super(message, cause); }
    }
}

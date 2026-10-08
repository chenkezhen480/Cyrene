package com.harness.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.env.MysqlConnectionPool;
import com.harness.core.persistence.SqlConnectionProvider;
import com.harness.core.model.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * MySQL persistence for tenant tool permissions: one row per tenant + identity.
 *
 * <p>Only tool <em>names</em> are stored. A tool's description and JSON Schema still come
 * from {@code ToolRegistry}, so a newly registered tool appears in the admin list with no
 * schema change and no second source of truth to drift. The stored list is what this identity
 * may <em>not</em> use, so an empty list means everything stays enabled — the same as an
 * absent row.</p>
 */
public class ToolPermissionStore {

    static final String PROFILE_TABLE = "tool_permission_profile";

    private static final Logger log = LoggerFactory.getLogger(ToolPermissionStore.class);

    private static final String TABLE_PRESENT_SQL = """
            SELECT COUNT(*)
            FROM information_schema.tables
            WHERE table_schema = DATABASE()
              AND table_name = ?
            """;

    private static final String FIND_PROFILE_SQL = """
            SELECT tenant_id, identity, disabled_tools_json
            FROM tool_permission_profile
            WHERE tenant_id <=> ? AND identity = ?
            """;

    private static final String SAVE_PROFILE_SQL = """
            INSERT INTO tool_permission_profile (tenant_id, identity, disabled_tools_json)
            VALUES (?, ?, CAST(? AS JSON))
            ON DUPLICATE KEY UPDATE disabled_tools_json = VALUES(disabled_tools_json)
            """;

    private static final String LIST_IDENTITIES_SQL = """
            SELECT identity FROM (
                (SELECT identity COLLATE utf8mb4_bin AS identity FROM tool_permission_profile
                 WHERE tenant_id <=> BINARY ? %s ORDER BY identity COLLATE utf8mb4_bin LIMIT ?)
                UNION
                (SELECT DISTINCT identity FROM internal_api_permission
                 WHERE tenant_id = ? %s ORDER BY identity COLLATE utf8mb4_bin LIMIT ?)
            ) profiles ORDER BY identity COLLATE utf8mb4_bin LIMIT ?
            """;

    private static final TypeReference<List<String>> TOOL_NAMES = new TypeReference<>() {
    };

    private final SqlConnectionProvider connectionProvider;
    private final ObjectMapper objectMapper;

    public ToolPermissionStore() {
        this(MysqlConnectionPool::getConnection, new ObjectMapper());
    }

    ToolPermissionStore(SqlConnectionProvider connectionProvider, ObjectMapper objectMapper) {
        this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Whether this deployment has the permission table at all. Docker only runs the schema
     * script on a fresh volume, so an older database legitimately has no such table — that
     * means the feature is off, not that the configuration is invalid.
     */
    boolean tablePresent() {
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(TABLE_PRESENT_SQL)) {
            statement.setString(1, PROFILE_TABLE);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getInt(1) == 1;
            }
        } catch (SQLException e) {
            throw new ToolPermissionException("Failed to probe tool permission table", e);
        }
    }

    /**
     * The tools this tenant + identity may not use, or empty when no row exists. Both an absent
     * row and an empty stored list mean "nothing is disabled".
     */
    Optional<Set<String>> findDisabledTools(String tenantId, String identity) {
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_PROFILE_SQL)) {
            statement.setString(1, normalize(tenantId));
            statement.setString(2, identity);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                requireExactScope(rows, normalize(tenantId), identity);
                return Optional.of(decodeToolNames(rows.getString("disabled_tools_json")));
            }
        } catch (SQLException e) {
            throw new ToolPermissionException("Failed to read tool permission profile", e);
        }
    }

    PageResponse<String> listIdentities(String tenantId, String cursor, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(LIST_IDENTITIES_SQL.formatted(
                     cursor == null ? "" : "AND identity COLLATE utf8mb4_bin > ?",
                     cursor == null ? "" : "AND identity COLLATE utf8mb4_bin > ?"))) {
            int parameter = 1;
            for (int source = 0; source < 2; source++) {
                statement.setString(parameter++, normalize(tenantId));
                if (cursor != null) statement.setString(parameter++, cursor);
                statement.setInt(parameter++, limit + 1);
            }
            statement.setInt(parameter, limit + 1);
            try (ResultSet rows = statement.executeQuery()) {
                List<String> identities = new ArrayList<>();
                while (rows.next()) {
                    identities.add(rows.getString("identity"));
                }
                return PageResponse.fromFetched(identities, limit, identity -> identity);
            }
        } catch (SQLException e) {
            throw new ToolPermissionException("Failed to list tool permission identities", e);
        }
    }


    void saveProfile(String tenantId, String identity, Set<String> disabledTools) {
        String tenant = required(tenantId, "tenantId");
        String scopedIdentity = required(identity, "identity");
        try (Connection connection = connectionProvider.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var lookup = connection.prepareStatement(FIND_PROFILE_SQL + " FOR UPDATE")) {
                    lookup.setString(1, tenant);
                    lookup.setString(2, scopedIdentity);
                    try (var rows = lookup.executeQuery()) {
                        if (rows.next()) requireExactScope(rows, tenant, scopedIdentity);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(SAVE_PROFILE_SQL)) {
                    statement.setString(1, tenant);
                    statement.setString(2, scopedIdentity);
                    statement.setString(3, encodeToolNames(disabledTools));
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                try { connection.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            }
        } catch (SQLException e) {
            throw new ToolPermissionException("Failed to save tool permission profile", e);
        }
    }

    private static void requireExactScope(ResultSet rows, String tenantId, String identity) throws SQLException {
        if (!Objects.equals(rows.getString("tenant_id"), tenantId) || !identity.equals(rows.getString("identity"))) {
            throw new SecurityException("Tool permission scope does not match verified identity");
        }
    }

    private String encodeToolNames(Set<String> toolNames) {
        Set<String> names = new LinkedHashSet<>(toolNames == null ? Set.of() : toolNames);
        try {
            return objectMapper.writeValueAsString(List.copyOf(names));
        } catch (Exception e) {
            throw new ToolPermissionException("Failed to encode disabled tool names", e);
        }
    }

    private Set<String> decodeToolNames(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            return Set.copyOf(objectMapper.readValue(json, TOOL_NAMES));
        } catch (Exception e) {
            log.warn("[ToolPermission] Stored disabled_tools_json is not a string array: {}", json);
            throw new ToolPermissionException("Stored disabled tool names are malformed", e);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}

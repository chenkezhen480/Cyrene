package com.harness.agent.graph;

import com.harness.core.model.PageResponse;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * MySQL-backed graph-space authorization activated by the optional binding table.
 */
public final class MysqlGraphSpaceAccessService implements GraphSpaceAccessService {

    static final String TABLE_NAME = "graph_space_bindings";

    private static final String LIST_SQL = """
            SELECT id, graph_id, schema_id, description
            FROM graph_space_bindings
            WHERE tenant_id = ?
              AND status = 'active'
              AND permission IN ('read', 'write', 'admin')
              AND id > ?
            ORDER BY id
            LIMIT ?
            """;

    private static final String AUTHORIZE_SQL = """
            SELECT 1
            FROM graph_space_bindings
            WHERE tenant_id = ?
              AND graph_id = ?
              AND schema_id = ?
              AND status = 'active'
              AND permission IN ('read', 'write', 'admin')
            LIMIT 1
            """;

    private static final String DELETE_BINDINGS_SQL = """
            DELETE FROM graph_space_bindings
            WHERE graph_id = ?
              AND schema_id = ?
            """;

    private final GraphSpaceAccessConnectionProvider connectionProvider;

    public MysqlGraphSpaceAccessService(
            GraphSpaceAccessConnectionProvider connectionProvider
    ) {
        this.connectionProvider = Objects.requireNonNull(
                connectionProvider,
                "connectionProvider"
        );
    }

    @Override
    public PageResponse<GraphSpaceReference> listReadable(
            String tenantId,
            int limit,
            String cursor
    ) {
        tenantId = requireTenantId(tenantId);
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }
        long cursorId = decodeCursor(cursor);
        List<BindingRow> fetched = new ArrayList<>();
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(LIST_SQL)) {
            statement.setString(1, tenantId);
            statement.setLong(2, cursorId);
            statement.setInt(3, limit + 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    fetched.add(new BindingRow(
                            resultSet.getLong("id"),
                            new GraphSpaceReference(
                                    resultSet.getString("graph_id"),
                                    resultSet.getString("schema_id"),
                                    resultSet.getString("description"))
                    ));
                }
            }
        } catch (SQLException e) {
            throw new GraphSpaceAccessException("Failed to list readable graph spaces", e);
        }

        PageResponse<BindingRow> bindingPage = PageResponse.fromFetched(
                fetched,
                limit,
                row -> encodeCursor(row.id())
        );
        return new PageResponse<>(
                bindingPage.items().stream().map(BindingRow::reference).toList(),
                bindingPage.pageInfo()
        );
    }

    @Override
    public void requireReadable(
            String tenantId,
            String graphId,
            String schemaId
    ) {
        tenantId = requireTenantId(tenantId);
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(AUTHORIZE_SQL)) {
            statement.setString(1, tenantId);
            statement.setString(2, graphId);
            statement.setString(3, schemaId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SecurityException("Graph space is not readable by the current access scope");
                }
            }
        } catch (SQLException e) {
            throw new GraphSpaceAccessException("Failed to authorize graph space access", e);
        }
    }

    @Override
    public int deleteBindings(String graphId, String schemaId) {
        graphId = requireText(graphId, "graphId");
        schemaId = requireText(schemaId, "schemaId");
        try (Connection connection = connectionProvider.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false);
                int deleted;
                try (PreparedStatement statement = connection.prepareStatement(DELETE_BINDINGS_SQL)) {
                    statement.setString(1, graphId);
                    statement.setString(2, schemaId);
                    deleted = statement.executeUpdate();
                }
                connection.commit();
                return deleted;
            } catch (SQLException e) {
                rollback(connection, e);
                throw e;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException e) {
            throw new GraphSpaceAccessException("Failed to delete graph-space bindings", e);
        }
    }

    private static String encodeCursor(long id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Long.toString(id).getBytes(StandardCharsets.UTF_8));
    }

    private static String requireTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        return tenantId.trim();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    private static void rollback(Connection connection, SQLException failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static long decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(cursor),
                    StandardCharsets.UTF_8
            );
            long cursorId = Long.parseLong(decoded);
            if (cursorId < 0) {
                throw new IllegalArgumentException("cursor must not be negative");
            }
            return cursorId;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid graph-space access cursor", e);
        }
    }

    private record BindingRow(long id, GraphSpaceReference reference) {
    }
}

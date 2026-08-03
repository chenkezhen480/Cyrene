package com.harness.agent.graph;

import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.env.MysqlConnectionPool;
import com.harness.graph.store.KnowledgeGraphStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

public final class GraphSpaceAccessServiceFactory {

    private static final Logger log = LoggerFactory.getLogger(GraphSpaceAccessServiceFactory.class);
    private static final String TABLE_EXISTS_SQL = """
            SELECT 1
            FROM information_schema.tables
            WHERE table_schema = DATABASE()
              AND table_name = ?
            LIMIT 1
            """;

    private GraphSpaceAccessServiceFactory() {
    }

    public static GraphSpaceAccessService create(KnowledgeGraphStore graphStore) {
        Objects.requireNonNull(graphStore, "graphStore");
        if ("none".equals(graphStore.providerName())) {
            return new OpenGraphSpaceAccessService(graphStore);
        }

        String relationalStore = EnvConfig.get().getString(EnvKey.AUDIT_STORE, "none");
        if (!"mysql".equalsIgnoreCase(relationalStore)) {
            log.info("[KnowledgeGraph] graph-space bindings inactive (MySQL storage is disabled)");
            return new OpenGraphSpaceAccessService(graphStore);
        }

        GraphSpaceAccessConnectionProvider connectionProvider = MysqlConnectionPool::getConnection;
        if (bindingTableExists(connectionProvider)) {
            log.info("[KnowledgeGraph] graph-space bindings active (table={})",
                    MysqlGraphSpaceAccessService.TABLE_NAME);
            return new MysqlGraphSpaceAccessService(connectionProvider);
        }

        log.info("[KnowledgeGraph] graph-space bindings inactive; optional table '{}' is not installed",
                MysqlGraphSpaceAccessService.TABLE_NAME);
        return new OpenGraphSpaceAccessService(graphStore);
    }

    private static boolean bindingTableExists(
            GraphSpaceAccessConnectionProvider connectionProvider
    ) {
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(TABLE_EXISTS_SQL)) {
            statement.setString(1, MysqlGraphSpaceAccessService.TABLE_NAME);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException e) {
            throw new GraphSpaceAccessException(
                    "Failed to detect optional graph-space binding table",
                    e
            );
        }
    }
}

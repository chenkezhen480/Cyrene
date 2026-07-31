package com.harness.graph.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.graph.neo4j.Neo4jKnowledgeGraphStore;
import com.harness.graph.schema.GraphSchemaRegistry;
import com.harness.graph.store.GraphStoreException;
import com.harness.graph.store.KnowledgeGraphStore;
import com.harness.graph.store.NoOpKnowledgeGraphStore;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Logging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

public final class KnowledgeGraphStoreFactory {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphStoreFactory.class);

    private KnowledgeGraphStoreFactory() {
    }

    public static KnowledgeGraphStore create(GraphSchemaRegistry schemaRegistry) {
        return create(GraphSettings.fromEnvironment(), schemaRegistry, new ObjectMapper());
    }

    static KnowledgeGraphStore create(
            GraphSettings settings,
            GraphSchemaRegistry schemaRegistry,
            ObjectMapper objectMapper
    ) {
        if (settings.provider() == GraphProvider.NONE) {
            log.info("[KnowledgeGraph] disabled (provider=none)");
            return new NoOpKnowledgeGraphStore();
        }

        Config driverConfig = Config.builder()
                .withLogging(Logging.slf4j())
                .withConnectionTimeout(settings.connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .withMaxConnectionPoolSize(settings.maxConnectionPoolSize())
                .build();
        Driver driver = GraphDatabase.driver(
                settings.neo4jUri(),
                AuthTokens.basic(settings.neo4jUser(), settings.neo4jPassword()),
                driverConfig
        );
        try {
            driver.verifyConnectivity();
            log.info("[KnowledgeGraph] connected to Neo4j database '{}'", settings.neo4jDatabase());
            return new Neo4jKnowledgeGraphStore(driver, settings, schemaRegistry, objectMapper);
        } catch (Exception e) {
            driver.close();
            throw new GraphStoreException("Neo4j connectivity verification failed: " + e.getMessage(), e);
        }
    }
}

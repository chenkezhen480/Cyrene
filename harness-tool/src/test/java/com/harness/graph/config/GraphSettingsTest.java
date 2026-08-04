package com.harness.graph.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.graph.schema.GraphSchemaRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphSettingsTest {

    @Test
    void defaultsToDisabledProviderWithoutNeo4jCredentials() {
        EnvConfig.init(Map.of());

        GraphSettings settings = GraphSettings.fromEnvironment();

        assertThat(settings.provider()).isEqualTo(GraphProvider.NONE);
        assertThat(settings.defaultLimit()).isEqualTo(50);
        assertThat(settings.maxDepth()).isEqualTo(2);
    }

    @Test
    void requiresPasswordWhenNeo4jIsEnabled() {
        EnvConfig.init(Map.of(
                EnvKey.GRAPH_PROVIDER, "neo4j",
                EnvKey.GRAPH_NEO4J_URI, "bolt://localhost:7687",
                EnvKey.GRAPH_NEO4J_USER, "neo4j",
                EnvKey.GRAPH_NEO4J_PASSWORD, ""
        ));

        assertThatThrownBy(GraphSettings::fromEnvironment)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("neo4jPassword");
    }

    @Test
    void enablesNeo4jWithOnlyProviderAndPassword() {
        EnvConfig.init(Map.of(
                EnvKey.GRAPH_PROVIDER, "neo4j",
                EnvKey.GRAPH_NEO4J_PASSWORD, "test-password"
        ));

        GraphSettings settings = GraphSettings.fromEnvironment();

        assertThat(settings.provider()).isEqualTo(GraphProvider.NEO4J);
        assertThat(settings.neo4jUri()).isEqualTo("bolt://localhost:7687");
        assertThat(settings.neo4jUser()).isEqualTo("neo4j");
        assertThat(settings.neo4jDatabase()).isEqualTo("neo4j");
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.queryTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(settings.maxConnectionPoolSize()).isEqualTo(20);
        assertThat(settings.defaultLimit()).isEqualTo(50);
        assertThat(settings.maxLimit()).isEqualTo(200);
        assertThat(settings.defaultMaxDepth()).isEqualTo(1);
        assertThat(settings.maxDepth()).isEqualTo(2);
        assertThat(settings.contextMaxItems()).isEqualTo(50);
        assertThat(settings.contextMaxChars()).isEqualTo(12_000);
    }

    @Test
    void capsLimitsAndDepth() {
        EnvConfig.init(Map.of(
                EnvKey.GRAPH_QUERY_DEFAULT_LIMIT, "20",
                EnvKey.GRAPH_QUERY_MAX_LIMIT, "100",
                EnvKey.GRAPH_QUERY_DEFAULT_MAX_DEPTH, "1",
                EnvKey.GRAPH_QUERY_MAX_DEPTH, "3"
        ));
        GraphSettings settings = GraphSettings.fromEnvironment();

        assertThat(settings.capLimit(500)).isEqualTo(100);
        assertThat(settings.capLimit(0)).isEqualTo(20);
        assertThat(settings.capDepth(8)).isEqualTo(3);
        assertThat(settings.capDepth(0)).isEqualTo(1);
    }

    @Test
    void rejectsUnsupportedProvider() {
        EnvConfig.init(Map.of(EnvKey.GRAPH_PROVIDER, "unknown"));

        assertThatThrownBy(GraphSettings::fromEnvironment)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported graph provider");
    }

    @Test
    void disabledProviderCreatesNoOpStoreWithoutNeo4jConnection() {
        EnvConfig.init(Map.of(EnvKey.GRAPH_PROVIDER, "none"));
        GraphSettings settings = GraphSettings.fromEnvironment();

        var store = KnowledgeGraphStoreFactory.create(
                settings, new GraphSchemaRegistry(), new ObjectMapper());

        assertThat(store.providerName()).isEqualTo("none");
    }
}

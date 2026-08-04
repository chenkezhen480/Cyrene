package com.harness.graph.config;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

public record GraphSettings(
        GraphProvider provider,
        String neo4jUri,
        String neo4jUser,
        String neo4jPassword,
        String neo4jDatabase,
        Duration connectTimeout,
        Duration queryTimeout,
        int maxConnectionPoolSize,
        int defaultLimit,
        int maxLimit,
        int defaultMaxDepth,
        int maxDepth,
        int contextMaxItems,
        int contextMaxChars
) {
    private static final Set<String> SUPPORTED_URI_SCHEMES = Set.of(
            "bolt", "bolt+s", "bolt+ssc", "neo4j", "neo4j+s", "neo4j+ssc");

    public GraphSettings {
        if (provider == null) {
            throw new IllegalArgumentException("provider is required");
        }
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(queryTimeout, "queryTimeout");
        requirePositive(maxConnectionPoolSize, "maxConnectionPoolSize");
        requirePositive(defaultLimit, "defaultLimit");
        requirePositive(maxLimit, "maxLimit");
        requirePositive(defaultMaxDepth, "defaultMaxDepth");
        requirePositive(maxDepth, "maxDepth");
        requirePositive(contextMaxItems, "contextMaxItems");
        requirePositive(contextMaxChars, "contextMaxChars");
        if (defaultLimit > maxLimit) {
            throw new IllegalArgumentException("defaultLimit cannot exceed maxLimit");
        }
        if (defaultMaxDepth > maxDepth) {
            throw new IllegalArgumentException("defaultMaxDepth cannot exceed maxDepth");
        }
        if (provider == GraphProvider.NEO4J) {
            requireText(neo4jUri, "neo4jUri");
            requireText(neo4jUser, "neo4jUser");
            requireText(neo4jPassword, "neo4jPassword");
            requireText(neo4jDatabase, "neo4jDatabase");
            validateNeo4jUri(neo4jUri);
        }
    }

    public static GraphSettings fromEnvironment() {
        return from(EnvConfig.get());
    }

    public static GraphSettings from(EnvConfig config) {
        GraphProvider provider = GraphProvider.parse(config.getString(EnvKey.GRAPH_PROVIDER, "none"));
        return new GraphSettings(
                provider,
                config.getString(EnvKey.GRAPH_NEO4J_URI, "bolt://localhost:7687"),
                config.getString(EnvKey.GRAPH_NEO4J_USER, "neo4j"),
                config.getString(EnvKey.GRAPH_NEO4J_PASSWORD, ""),
                config.getString(EnvKey.GRAPH_NEO4J_DATABASE, "neo4j"),
                Duration.ofSeconds(config.getInt(EnvKey.GRAPH_CONNECT_TIMEOUT_SECONDS, 10)),
                Duration.ofSeconds(config.getInt(EnvKey.GRAPH_QUERY_TIMEOUT_SECONDS, 15)),
                config.getInt(EnvKey.GRAPH_MAX_CONNECTION_POOL_SIZE, 20),
                config.getInt(EnvKey.GRAPH_QUERY_DEFAULT_LIMIT, 50),
                config.getInt(EnvKey.GRAPH_QUERY_MAX_LIMIT, 200),
                config.getInt(EnvKey.GRAPH_QUERY_DEFAULT_MAX_DEPTH, 1),
                config.getInt(EnvKey.GRAPH_QUERY_MAX_DEPTH, 2),
                config.getInt(EnvKey.GRAPH_CONTEXT_MAX_ITEMS, 50),
                config.getInt(EnvKey.GRAPH_CONTEXT_MAX_CHARS, 12_000)
        );
    }

    public int capLimit(int requestedLimit) {
        if (requestedLimit <= 0) {
            return defaultLimit;
        }
        return Math.min(requestedLimit, maxLimit);
    }

    public int capDepth(int requestedDepth) {
        if (requestedDepth <= 0) {
            return defaultMaxDepth;
        }
        return Math.min(requestedDepth, maxDepth);
    }

    private static void validateNeo4jUri(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() == null || !SUPPORTED_URI_SCHEMES.contains(uri.getScheme().toLowerCase())) {
                throw new IllegalArgumentException("Unsupported Neo4j URI scheme: " + uri.getScheme());
            }
            if (uri.getHost() == null && !"bolt+unix".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("Neo4j URI must contain a host");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Neo4j URI: " + value, e);
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required when graph provider is neo4j");
        }
    }

    private static void requirePositive(Duration value, String fieldName) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }

    private static void requirePositive(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be greater than 0");
        }
    }
}

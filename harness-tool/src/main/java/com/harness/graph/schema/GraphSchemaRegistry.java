package com.harness.graph.schema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

public class GraphSchemaRegistry {

    private final Map<String, GraphSchemaDefinition> schemas = new ConcurrentHashMap<>();

    public GraphSchemaRegistry() {
    }

    public GraphSchemaRegistry(List<GraphSchemaProvider> providers) {
        if (providers != null) {
            providers.forEach(provider -> {
                GraphSchemaDefinition definition = provider.definition();
                if (!provider.schemaId().equals(definition.schemaId())) {
                    throw new IllegalArgumentException(
                            "Graph schema provider ID does not match its definition: " + provider.schemaId());
                }
                register(definition);
            });
        }
    }

    public static GraphSchemaRegistry fromServiceLoader() {
        List<GraphSchemaProvider> providers = ServiceLoader
                .load(GraphSchemaProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        return new GraphSchemaRegistry(providers);
    }

    public void register(GraphSchemaDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("definition is required");
        }
        GraphSchemaDefinition previous = schemas.putIfAbsent(definition.schemaId(), definition);
        if (previous != null) {
            throw new IllegalStateException("Graph schema already registered: " + definition.schemaId());
        }
    }

    public GraphSchemaDefinition replace(GraphSchemaDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("definition is required");
        }
        GraphSchemaDefinition previous = schemas.replace(definition.schemaId(), definition);
        if (previous == null) {
            throw new IllegalStateException("Graph schema is not registered: " + definition.schemaId());
        }
        return previous;
    }

    public GraphSchemaDefinition unregister(String schemaId) {
        GraphSchemaDefinition removed = schemas.remove(schemaId);
        if (removed == null) {
            throw new IllegalStateException("Graph schema is not registered: " + schemaId);
        }
        return removed;
    }

    public Optional<GraphSchemaDefinition> find(String schemaId) {
        return Optional.ofNullable(schemas.get(schemaId));
    }

    public GraphSchemaDefinition require(String schemaId) {
        GraphSchemaDefinition definition = schemas.get(schemaId);
        if (definition == null) {
            throw new GraphSchemaValidationException("Unknown graph schema: " + schemaId);
        }
        return definition;
    }

    public List<GraphSchemaDefinition> list() {
        List<GraphSchemaDefinition> result = new ArrayList<>(schemas.values());
        result.sort(Comparator.comparing(GraphSchemaDefinition::schemaId));
        return List.copyOf(result);
    }
}

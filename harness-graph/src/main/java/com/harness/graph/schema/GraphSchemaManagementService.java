package com.harness.graph.schema;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

public final class GraphSchemaManagementService {

    private final GraphSchemaRegistry registry;
    private final GraphSchemaRepository repository;
    private final GraphSchemaDocumentCodec codec;
    private final Set<String> spiSchemaIds;

    public GraphSchemaManagementService(
            GraphSchemaRegistry registry,
            GraphSchemaRepository repository,
            GraphSchemaDocumentCodec codec
    ) {
        this.registry = registry;
        this.repository = repository;
        this.codec = codec;
        this.spiSchemaIds = registry.list().stream()
                .map(GraphSchemaDefinition::schemaId)
                .collect(Collectors.toUnmodifiableSet());
        loadManagedSchemas();
    }

    public static GraphSchemaManagementService open(Path storageDirectory, GraphSchemaRegistry registry) {
        GraphSchemaDocumentCodec codec = GraphSchemaDocumentCodec.createDefault();
        return new GraphSchemaManagementService(
                registry,
                new FileGraphSchemaRepository(storageDirectory, codec.jsonMapper()),
                codec
        );
    }

    public synchronized List<GraphSchemaSummary> list() {
        List<GraphSchemaSummary> summaries = new ArrayList<>();
        spiSchemaIds.stream()
                .map(registry::require)
                .map(this::toSpiSummary)
                .forEach(summaries::add);
        repository.list().stream()
                .map(this::toManagedSummary)
                .forEach(summaries::add);
        summaries.sort(Comparator.comparing(GraphSchemaSummary::schemaId));
        return List.copyOf(summaries);
    }

    public synchronized GraphSchemaDetails get(String schemaId) {
        if (spiSchemaIds.contains(schemaId)) {
            GraphSchemaDefinition definition = registry.require(schemaId);
            return new GraphSchemaDetails(
                    definition,
                    true,
                    GraphSchemaSource.SPI,
                    GraphSchemaFormat.JAVA,
                    false,
                    codec.render(GraphSchemaFormat.JSON, definition)
            );
        }
        StoredGraphSchema stored = requireManaged(schemaId);
        return new GraphSchemaDetails(
                stored.definition(),
                stored.enabled(),
                GraphSchemaSource.MANAGED,
                stored.format(),
                true,
                codec.render(stored.format(), stored.definition())
        );
    }

    public synchronized GraphSchemaDetails create(
            GraphSchemaFormat format,
            String content,
            boolean enabled
    ) {
        GraphSchemaDefinition definition = codec.parse(format, content);
        String schemaId = definition.schemaId();
        if (spiSchemaIds.contains(schemaId) || repository.find(schemaId).isPresent()) {
            throw new IllegalStateException("Graph schema already exists: " + schemaId);
        }

        StoredGraphSchema stored = new StoredGraphSchema(enabled, format, definition);
        repository.save(stored);
        if (enabled) {
            try {
                registry.register(definition);
            } catch (RuntimeException e) {
                rollbackCreate(schemaId, e);
                throw e;
            }
        }
        return get(schemaId);
    }

    public synchronized GraphSchemaDetails update(
            String schemaId,
            GraphSchemaFormat format,
            String content
    ) {
        StoredGraphSchema previous = requireManaged(schemaId);
        GraphSchemaDefinition definition = codec.parse(format, content);
        if (!schemaId.equals(definition.schemaId())) {
            throw new IllegalArgumentException("schemaId in content must match path schemaId");
        }

        StoredGraphSchema updated = new StoredGraphSchema(previous.enabled(), format, definition);
        if (!previous.enabled()) {
            repository.save(updated);
            return get(schemaId);
        }

        registry.replace(definition);
        try {
            repository.save(updated);
        } catch (RuntimeException e) {
            registry.replace(previous.definition());
            throw e;
        }
        return get(schemaId);
    }

    public synchronized GraphSchemaDetails enable(String schemaId) {
        StoredGraphSchema previous = requireManaged(schemaId);
        if (previous.enabled()) {
            return get(schemaId);
        }

        registry.register(previous.definition());
        try {
            repository.save(new StoredGraphSchema(true, previous.format(), previous.definition()));
        } catch (RuntimeException e) {
            registry.unregister(schemaId);
            throw e;
        }
        return get(schemaId);
    }

    public synchronized GraphSchemaDetails disable(String schemaId) {
        StoredGraphSchema previous = requireManaged(schemaId);
        if (!previous.enabled()) {
            return get(schemaId);
        }

        registry.unregister(schemaId);
        try {
            repository.save(new StoredGraphSchema(false, previous.format(), previous.definition()));
        } catch (RuntimeException e) {
            registry.register(previous.definition());
            throw e;
        }
        return get(schemaId);
    }

    public synchronized void delete(String schemaId) {
        StoredGraphSchema stored = requireManaged(schemaId);
        if (stored.enabled()) {
            throw new IllegalStateException("Disable graph schema before deleting it: " + schemaId);
        }
        repository.delete(schemaId);
    }

    private void loadManagedSchemas() {
        for (StoredGraphSchema stored : repository.list()) {
            String schemaId = stored.definition().schemaId();
            if (spiSchemaIds.contains(schemaId)) {
                throw new IllegalStateException(
                        "Managed graph schema conflicts with SPI schema: " + schemaId);
            }
            if (stored.enabled()) {
                registry.register(stored.definition());
            }
        }
    }

    private StoredGraphSchema requireManaged(String schemaId) {
        if (spiSchemaIds.contains(schemaId)) {
            throw new IllegalStateException("SPI graph schemas are read-only: " + schemaId);
        }
        return repository.find(schemaId)
                .orElseThrow(() -> new NoSuchElementException("Managed graph schema was not found: " + schemaId));
    }

    private void rollbackCreate(String schemaId, RuntimeException registrationFailure) {
        try {
            repository.delete(schemaId);
        } catch (RuntimeException rollbackFailure) {
            registrationFailure.addSuppressed(rollbackFailure);
        }
    }

    private GraphSchemaSummary toSpiSummary(GraphSchemaDefinition definition) {
        return new GraphSchemaSummary(
                definition.schemaId(),
                definition.version(),
                definition.mode(),
                true,
                GraphSchemaSource.SPI,
                GraphSchemaFormat.JAVA,
                false,
                definition.nodeTypes().size(),
                definition.relationTypes().size()
        );
    }

    private GraphSchemaSummary toManagedSummary(StoredGraphSchema stored) {
        GraphSchemaDefinition definition = stored.definition();
        return new GraphSchemaSummary(
                definition.schemaId(),
                definition.version(),
                definition.mode(),
                stored.enabled(),
                GraphSchemaSource.MANAGED,
                stored.format(),
                true,
                definition.nodeTypes().size(),
                definition.relationTypes().size()
        );
    }
}

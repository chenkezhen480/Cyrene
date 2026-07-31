package com.harness.graph.schema;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public final class FileGraphSchemaRepository implements GraphSchemaRepository {

    private static final String FILE_SUFFIX = ".schema.json";

    private final Path storageDirectory;
    private final ObjectMapper objectMapper;

    public FileGraphSchemaRepository(Path storageDirectory, ObjectMapper objectMapper) {
        this.storageDirectory = storageDirectory.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        try {
            Files.createDirectories(this.storageDirectory);
        } catch (IOException e) {
            throw new GraphSchemaPersistenceException(
                    "Failed to create graph schema directory: " + this.storageDirectory, e);
        }
    }

    @Override
    public List<StoredGraphSchema> list() {
        try (Stream<Path> files = Files.list(storageDirectory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(FILE_SUFFIX))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(this::read)
                    .toList();
        } catch (IOException e) {
            throw new GraphSchemaPersistenceException(
                    "Failed to list graph schemas from: " + storageDirectory, e);
        }
    }

    @Override
    public Optional<StoredGraphSchema> find(String schemaId) {
        return Optional.of(resolveSchemaFile(schemaId))
                .filter(Files::isRegularFile)
                .map(this::read);
    }

    @Override
    public void save(StoredGraphSchema schema) {
        Path target = resolveSchemaFile(schema.definition().schemaId());
        Path temporary = storageDirectory.resolve(
                schema.definition().schemaId() + "." + UUID.randomUUID() + ".tmp");
        try {
            byte[] content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(schema);
            Files.write(
                    temporary,
                    content,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );
            moveAtomically(temporary, target);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw new GraphSchemaPersistenceException(
                    "Failed to persist graph schema: " + schema.definition().schemaId(), e);
        }
    }

    @Override
    public void delete(String schemaId) {
        Path target = resolveSchemaFile(schemaId);
        try {
            if (!Files.deleteIfExists(target)) {
                throw new IllegalStateException("Managed graph schema was not found: " + schemaId);
            }
        } catch (IOException e) {
            throw new GraphSchemaPersistenceException("Failed to delete graph schema: " + schemaId, e);
        }
    }

    private StoredGraphSchema read(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            StoredGraphSchema schema = objectMapper.readValue(content, StoredGraphSchema.class);
            Path expectedPath = resolveSchemaFile(schema.definition().schemaId());
            if (!expectedPath.equals(path.toAbsolutePath().normalize())) {
                throw new GraphSchemaPersistenceException(
                        "Graph schema file name does not match schemaId: " + path.getFileName(),
                        new IllegalArgumentException(schema.definition().schemaId()));
            }
            return schema;
        } catch (IOException e) {
            throw new GraphSchemaPersistenceException("Failed to read graph schema file: " + path, e);
        }
    }

    private Path resolveSchemaFile(String schemaId) {
        GraphSchemaSupport.requireSchemaId(schemaId);
        Path resolved = storageDirectory.resolve(schemaId + FILE_SUFFIX).normalize();
        if (!resolved.getParent().equals(storageDirectory)) {
            throw new IllegalArgumentException("Invalid graph schema ID");
        }
        return resolved;
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

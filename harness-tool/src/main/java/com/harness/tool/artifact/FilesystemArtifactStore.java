package com.harness.tool.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.harness.core.model.Artifact;
import com.harness.core.model.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Filesystem-based artifact store.
 * Metadata: {artifactDir}/{id}.meta.json
 * File:     {artifactDir}/{id}/{filename}
 */
public class FilesystemArtifactStore implements ArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(FilesystemArtifactStore.class);
    private final Path artifactDir;
    private final ObjectMapper mapper;

    public FilesystemArtifactStore(Path artifactDir) {
        this.artifactDir = artifactDir;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        try {
            Files.createDirectories(artifactDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create artifact directory: " + artifactDir, e);
        }
    }

    @Override
    public void save(Artifact artifact) {
        try {
            Path metaFile = artifactDir.resolve(artifact.id() + ".meta.json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(metaFile.toFile(), artifact);
            log.debug("Saved artifact metadata: {} ({})", artifact.id(), artifact.name());
        } catch (IOException e) {
            throw new RuntimeException("Failed to save artifact metadata: " + artifact.id(), e);
        }
    }

    @Override
    public Optional<Artifact> get(String id) {
        Path metaFile = safeResolve(id + ".meta.json");
        if (metaFile == null || !Files.exists(metaFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(metaFile.toFile(), Artifact.class));
        } catch (IOException e) {
            log.warn("Failed to read artifact metadata: {}: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void delete(String id) {
        // Delete metadata file
        Path metaFile = safeResolve(id + ".meta.json");
        if (metaFile != null) {
            try {
                Files.deleteIfExists(metaFile);
            } catch (IOException e) {
                log.warn("Failed to delete artifact metadata: {}: {}", id, e.getMessage());
            }
        }
        // Delete artifact file directory
        Path fileDir = safeResolve(id);
        if (fileDir != null) {
            try {
                if (Files.exists(fileDir)) {
                    try (Stream<Path> walk = Files.walk(fileDir)) {
                        walk.sorted(Comparator.reverseOrder())
                                .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to delete artifact files: {}: {}", id, e.getMessage());
            }
        }
        log.debug("Deleted artifact: {}", id);
    }

    @Override
    public List<Artifact> listBySession(String sessionId) {
        List<Artifact> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(artifactDir, "*.meta.json")) {
            for (Path metaFile : stream) {
                try {
                    Artifact artifact = mapper.readValue(metaFile.toFile(), Artifact.class);
                    if (sessionId.equals(artifact.sessionId())) {
                        result.add(artifact);
                    }
                } catch (IOException e) {
                    log.debug("Skipping corrupt metadata file: {}", metaFile.getFileName());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list artifacts for session {}: {}", sessionId, e.getMessage());
        }
        result.sort(Comparator.comparing(Artifact::createdAt));
        return result;
    }

    /**
     * Resolve a path within artifactDir with path traversal protection.
     * Returns null if the resolved path escapes artifactDir.
     */
    private Path safeResolve(String name) {
        Path resolved = artifactDir.resolve(name).normalize();
        if (!resolved.startsWith(artifactDir)) {
            log.warn("Path traversal attempt blocked: {}", name);
            return null;
        }
        return resolved;
    }
}

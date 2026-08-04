package com.harness.tool.artifact;

import com.harness.core.model.Artifact;
import com.harness.core.model.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.UUID;

/**
 * High-level artifact storage service.
 * Handles storing files (from byte arrays or paths), MIME inference, and size limits.
 */
public class ArtifactStorageService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactStorageService.class);

    private final ArtifactStore store;
    private final Path artifactDir;
    private final long maxSizeBytes;

    /**
     * @param store      artifact metadata store
     * @param artifactDir base directory for artifact files
     * @param maxSizeMB   max single file size in MB
     */
    public ArtifactStorageService(ArtifactStore store, Path artifactDir, int maxSizeMB) {
        this.store = store;
        this.artifactDir = artifactDir;
        this.maxSizeBytes = maxSizeMB * 1024L * 1024L;
    }

    /**
     * Store artifact from byte array.
     */
    public Artifact store(byte[] data, String name, String mimeType, String sessionId) {
        if (data.length > maxSizeBytes) {
            throw new IllegalArgumentException("File size " + data.length + " exceeds limit " + maxSizeBytes);
        }
        String id = UUID.randomUUID().toString();
        Path fileDir = artifactDir.resolve(id);
        try {
            Files.createDirectories(fileDir);
            Path filePath = fileDir.resolve(name);
            Files.write(filePath, data);
            return saveMetadata(id, sessionId, name, mimeType, data.length, filePath);
        } catch (IOException e) {
            cleanup(fileDir);
            throw new RuntimeException("Failed to store artifact: " + name, e);
        }
    }

    /**
     * Store artifact from an existing file path (moves the file).
     */
    public Artifact storeFromPath(Path source, String name, String mimeType, String sessionId) {
        try {
            long size = Files.size(source);
            if (size > maxSizeBytes) {
                throw new IllegalArgumentException("File size " + size + " exceeds limit " + maxSizeBytes);
            }
            String id = UUID.randomUUID().toString();
            Path fileDir = artifactDir.resolve(id);
            Files.createDirectories(fileDir);
            Path target = fileDir.resolve(name);
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            return saveMetadata(id, sessionId, name, mimeType, size, target);
        } catch (IOException e) {
            throw new RuntimeException("Failed to store artifact from path: " + source, e);
        }
    }

    private Artifact saveMetadata(String id, String sessionId, String name, String mimeType, long size, Path filePath) {
        Instant now = Instant.now();
        Artifact artifact = new Artifact(
                id, sessionId, name,
                Artifact.inferType(mimeType),
                mimeType, size,
                filePath.toAbsolutePath().toString(),
                now
        );
        store.save(artifact);
        log.info("Stored artifact: {} ({}, {} bytes) for session {}", name, mimeType, size, sessionId);
        return artifact;
    }

    private void cleanup(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
                }
            }
        } catch (IOException ignored) {}
    }
}

package com.harness.tool.artifact;

import com.harness.core.model.Artifact;
import com.harness.core.model.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;
import java.util.Optional;
import java.util.NoSuchElementException;

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
        sessionId = ownedSession(sessionId);
        validateName(name);
        String id = UUID.randomUUID().toString();
        Path fileDir = artifactDir.resolve(id);
        try {
            Files.createDirectories(fileDir);
            Path filePath = fileDir.resolve(name);
            Files.write(filePath, data);
            return saveMetadata(id, sessionId, name, mimeType, data.length, filePath);
        } catch (IOException | RuntimeException e) {
            try {
                store.delete(id);
            } catch (RuntimeException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            cleanup(fileDir);
            throw new RuntimeException("Failed to store artifact: " + name, e);
        }
    }

    /**
     * Store artifact from an existing file path (moves the file).
     */
    public Artifact storeFromPath(Path source, String name, String mimeType, String sessionId) {
        sessionId = ownedSession(sessionId);
        validateName(name);
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

    /** Internal payloads never receive metadata in the public ArtifactStore. */
    public byte[] readGraphDraftPayload(String id) {
        requireUuid(id);
        try {
            Path payload = graphDraftPayloads().resolve(id + ".json");
            if (!Files.exists(payload)) throw new NoSuchElementException("Graph draft payload not found: " + id);
            return readBounded(confined(payload));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read graph draft payload", e);
        }
    }

    public void writeGraphDraftPayload(String id, byte[] content) {
        publishGraphDraftFile(id, content, true);
    }

    public Optional<byte[]> readGraphDraftReference(String id) {
        requireUuid(id);
        try {
            Path reference = graphDraftReferences().resolve(id + ".json");
            return Files.exists(reference) ? Optional.of(readBounded(confined(reference))) : Optional.empty();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read graph draft reference", e);
        }
    }

    /** Publish one small current/version reference only after its immutable payload is durable. */
    public void writeGraphDraftReference(String id, byte[] content) {
        publishGraphDraftFile(id, content, false);
    }

    private void publishGraphDraftFile(String id, byte[] content, boolean immutablePayload) {
        requireUuid(id);
        if (content.length > maxSizeBytes) throw new IllegalArgumentException("Graph draft exceeds artifact limit");
        Path temporary = null;
        try {
            Path directory = immutablePayload ? graphDraftPayloads() : graphDraftReferences();
            Path target = directory.resolve(id + ".json");
            if (immutablePayload && Files.exists(target)) throw new IllegalStateException("Graph draft payload already exists: " + id);
            temporary = Files.createTempFile(directory, ".draft-", ".tmp");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            if (immutablePayload) Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            else Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to publish graph draft " + (immutablePayload ? "payload" : "reference"), e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException e) { log.warn("Failed to remove temporary draft reference: {}", temporary, e); }
            }
        }
    }

    private Path graphDraftReferences() throws IOException {
        Path directory = artifactDir.toAbsolutePath().normalize().resolve("graph-drafts");
        Files.createDirectories(directory);
        return confined(directory);
    }

    private Path graphDraftPayloads() throws IOException {
        Path directory = graphDraftReferences().resolve("payloads");
        Files.createDirectories(directory);
        return confined(directory);
    }

    private Path confined(Path path) throws IOException {
        Path real = path.toRealPath();
        if (!real.startsWith(artifactDir.toRealPath())) throw new SecurityException("Draft reference escapes artifact root");
        return real;
    }

    private byte[] readBounded(Path file) throws IOException {
        if (Files.size(file) > maxSizeBytes) throw new IllegalStateException("Graph draft exceeds artifact limit");
        try (var input = Files.newInputStream(file)) {
            byte[] data = input.readNBytes(Math.toIntExact(Math.min(maxSizeBytes + 1, Integer.MAX_VALUE)));
            if (data.length > maxSizeBytes) throw new IllegalStateException("Graph draft exceeds artifact limit");
            return data;
        }
    }

    private static void requireUuid(String id) {
        if (id == null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Invalid draft artifact identifier");
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || Path.of(name).isAbsolute()
                || Path.of(name).getNameCount() != 1) {
            throw new IllegalArgumentException("Artifact name must be a single file name");
        }
    }

    private static String ownedSession(String sessionId) {
        String currentSessionId = ArtifactSessionContext.current();
        if (currentSessionId != null) {
            if (sessionId != null && !sessionId.equals(currentSessionId)) {
                throw new SecurityException("Artifact session does not match the executing run");
            }
            sessionId = currentSessionId;
        }
        return sessionId;
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

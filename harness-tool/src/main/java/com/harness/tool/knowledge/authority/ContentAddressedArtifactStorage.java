package com.harness.tool.knowledge.authority;

import com.harness.core.knowledge.KnowledgeArtifactType;
import com.harness.core.knowledge.KnowledgeIdentity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/** Immutable, content-addressed file storage constrained to one configured root. */
public final class ContentAddressedArtifactStorage {

    private final Path root;
    private final Path objectRoot;
    private final Path stagingRoot;

    public ContentAddressedArtifactStorage(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("root is required");
        }
        this.root = root.toAbsolutePath().normalize();
        this.objectRoot = this.root.resolve("objects");
        this.stagingRoot = this.root.resolve(".staging");
        try {
            Files.createDirectories(objectRoot);
            Files.createDirectories(stagingRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize artifact storage", e);
        }
    }

    public StoredArtifact store(
            byte[] content,
            String tenantId,
            String collectionKey,
            KnowledgeArtifactType artifactType
    ) {
        if (content == null) {
            throw new IllegalArgumentException("content is required");
        }
        if (collectionKey == null || collectionKey.isBlank()) {
            throw new IllegalArgumentException("collectionKey is required");
        }
        if (artifactType == null) {
            throw new IllegalArgumentException("artifactType is required");
        }
        String contentHash = KnowledgeIdentity.sha256(content);
        String scopeHash = KnowledgeIdentity.sha256(
                normalizeTenant(tenantId) + collectionKey.trim());
        Path destination = objectRoot
                .resolve(scopeHash)
                .resolve(artifactType.name().toLowerCase(Locale.ROOT))
                .resolve(contentHash.substring(0, 2))
                .resolve(contentHash)
                .normalize();
        requireInsideRoot(destination);

        Path staged = null;
        try {
            if (!Files.exists(destination)) {
                Files.createDirectories(destination.getParent());
                staged = Files.createTempFile(stagingRoot, "artifact-", ".tmp");
                Files.write(staged, content, StandardOpenOption.TRUNCATE_EXISTING);
                try {
                    Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE);
                    staged = null;
                } catch (java.nio.file.FileAlreadyExistsException ignored) {
                    // An identical content-addressed object won the race.
                }
            }
            verifyContent(destination, contentHash);
            return new StoredArtifact(contentHash, destination.toUri().toString(), content.length);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store immutable artifact", e);
        } finally {
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException ignored) {
                    // The reconciler reports stale staging files; original error wins.
                }
            }
        }
    }

    public Path objectRoot() {
        return objectRoot;
    }

    public Path resolveStorageUri(String storageUri) {
        if (storageUri == null || storageUri.isBlank()) {
            throw new IllegalArgumentException("storageUri is required");
        }
        Path path;
        try {
            path = Path.of(java.net.URI.create(storageUri)).toAbsolutePath().normalize();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("storageUri must be a file URI", e);
        }
        requireInsideRoot(path);
        return path;
    }

    private void verifyContent(Path destination, String expectedHash) throws IOException {
        if (!Files.isRegularFile(destination)) {
            throw new IOException("Artifact destination is not a regular file");
        }
        String actualHash = KnowledgeIdentity.sha256(Files.readAllBytes(destination));
        if (!actualHash.equals(expectedHash)) {
            throw new IOException("Content-addressed artifact hash mismatch");
        }
    }

    private void requireInsideRoot(Path path) {
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IllegalArgumentException("artifact path is outside the configured root");
        }
    }

    private static String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "" : tenantId.trim();
    }

    public record StoredArtifact(String contentHash, String storageUri, long byteLength) {
    }
}

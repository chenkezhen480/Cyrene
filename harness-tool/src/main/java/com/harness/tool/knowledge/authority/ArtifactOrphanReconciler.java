package com.harness.tool.knowledge.authority;

import com.harness.core.model.PageResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Reports unregistered object files. It never deletes or scans outside the configured object root. */
public final class ArtifactOrphanReconciler {

    private final ContentAddressedArtifactStorage storage;
    private final KnowledgeArtifactRepository repository;

    public ArtifactOrphanReconciler(
            ContentAddressedArtifactStorage storage,
            KnowledgeArtifactRepository repository
    ) {
        this.storage = java.util.Objects.requireNonNull(storage, "storage");
        this.repository = java.util.Objects.requireNonNull(repository, "repository");
    }

    public PageResponse<OrphanArtifact> findOrphans(
            Instant modifiedBefore,
            String afterRelativePath,
            int limit
    ) {
        if (modifiedBefore == null) {
            throw new IllegalArgumentException("modifiedBefore is required");
        }
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        String cursor = afterRelativePath == null ? "" : afterRelativePath;
        Path objectRoot = storage.objectRoot();
        try (Stream<Path> paths = Files.walk(objectRoot)) {
            List<OrphanArtifact> fetched = paths
                    .filter(Files::isRegularFile)
                    .map(path -> describe(objectRoot, path))
                    .filter(orphan -> orphan.relativePath().compareTo(cursor) > 0)
                    .filter(orphan -> orphan.modifiedAt().isBefore(modifiedBefore))
                    .filter(orphan -> !repository.storageUriExists(orphan.storageUri()))
                    .sorted(Comparator.comparing(OrphanArtifact::relativePath))
                    .limit((long) limit + 1)
                    .toList();
            return PageResponse.fromFetched(
                    fetched, limit, OrphanArtifact::relativePath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan artifact object root", e);
        }
    }

    private static OrphanArtifact describe(Path root, Path path) {
        try {
            return new OrphanArtifact(
                    root.relativize(path).toString().replace('\\', '/'),
                    path.toUri().toString(),
                    Files.size(path),
                    Files.getLastModifiedTime(path).toInstant());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to inspect artifact " + path.getFileName(), e);
        }
    }

    public record OrphanArtifact(
            String relativePath,
            String storageUri,
            long byteLength,
            Instant modifiedAt
    ) {
    }
}

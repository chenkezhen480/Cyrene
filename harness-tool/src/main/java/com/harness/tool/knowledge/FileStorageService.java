package com.harness.tool.knowledge;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
    private final Path uploadDir;

    public FileStorageService() {
        EnvConfig cfg = EnvConfig.get();
        String dir = cfg.getString(EnvKey.KNOWLEDGE_UPLOAD_DIR, "uploads");
        this.uploadDir = Paths.get(dir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(uploadDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create upload directory: " + uploadDir, e);
        }
    }

    public String store(byte[] data, String fileName, String collection) {
        Path collectionDir = resolveCollection(collection);
        try {
            Files.createDirectories(collectionDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create collection directory: " + collectionDir, e);
        }

        String safeName = fileName.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        String storedName = UUID.randomUUID().toString().substring(0, 8) + "_" + safeName;
        Path filePath = collectionDir.resolve(storedName).normalize();

        try {
            Files.write(filePath, data);
            log.debug("Stored file: {} ({}KB)", filePath, data.length / 1024);
            return filePath.toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file: " + filePath, e);
        }
    }

    public boolean delete(String path) {
        Path storedPath = resolveStoredPath(path);
        try {
            return Files.deleteIfExists(storedPath);
        } catch (IOException e) {
            log.warn("Failed to delete file {}: {}", storedPath, e.getMessage());
            return false;
        }
    }

    public void deleteCollection(String collection) {
        Path collectionDir = resolveCollection(collection);
        if (!Files.exists(collectionDir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(collectionDir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to delete collection files: " + collection, e);
        }
    }

    public Optional<byte[]> load(String path) {
        Path storedPath = resolveStoredPath(path);
        try {
            return Optional.of(Files.readAllBytes(storedPath));
        } catch (IOException e) {
            log.warn("Failed to load file {}: {}", storedPath, e.getMessage());
            return Optional.empty();
        }
    }

    private Path resolveCollection(String collection) {
        if (collection == null || collection.isBlank()) {
            throw new IllegalArgumentException("collection is required");
        }
        Path resolved = uploadDir.resolve(collection).normalize();
        if (!resolved.startsWith(uploadDir) || resolved.equals(uploadDir)) {
            throw new IllegalArgumentException("Invalid knowledge collection path");
        }
        return resolved;
    }

    private Path resolveStoredPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("stored file path is required");
        }
        Path candidate = Paths.get(path);
        Path resolved = candidate.isAbsolute()
                ? candidate.normalize()
                : candidate.toAbsolutePath().normalize();
        if (!resolved.startsWith(uploadDir) || resolved.equals(uploadDir)) {
            throw new IllegalArgumentException("Stored file path is outside the upload directory");
        }
        return resolved;
    }
}

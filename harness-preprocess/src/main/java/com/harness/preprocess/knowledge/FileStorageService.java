package com.harness.preprocess.knowledge;

import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.UUID;

public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
    private final Path uploadDir;

    public FileStorageService() {
        EnvConfig cfg = EnvConfig.get();
        String dir = cfg.getString(EnvKey.KNOWLEDGE_UPLOAD_DIR, "uploads");
        this.uploadDir = Paths.get(dir);
        try {
            Files.createDirectories(uploadDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create upload directory: " + uploadDir, e);
        }
    }

    public String store(byte[] data, String fileName, String collection) {
        Path collectionDir = uploadDir.resolve(collection);
        try {
            Files.createDirectories(collectionDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create collection directory: " + collectionDir, e);
        }

        String safeName = fileName.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        String storedName = UUID.randomUUID().toString().substring(0, 8) + "_" + safeName;
        Path filePath = collectionDir.resolve(storedName);

        try {
            Files.write(filePath, data);
            log.debug("Stored file: {} ({}KB)", filePath, data.length / 1024);
            return filePath.toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file: " + filePath, e);
        }
    }

    public boolean delete(String path) {
        try {
            return Files.deleteIfExists(Paths.get(path));
        } catch (IOException e) {
            log.warn("Failed to delete file {}: {}", path, e.getMessage());
            return false;
        }
    }

    public Optional<byte[]> load(String path) {
        try {
            return Optional.of(Files.readAllBytes(Paths.get(path)));
        } catch (IOException e) {
            log.warn("Failed to load file {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }
}

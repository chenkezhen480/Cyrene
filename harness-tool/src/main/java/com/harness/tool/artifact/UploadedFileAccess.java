package com.harness.tool.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.security.RequestPrincipal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Authorizes immutable input uploads before any HTTP or model tool reads their bytes. */
public final class UploadedFileAccess {
    private final Path uploadRoot;
    private final ObjectMapper mapper;

    public UploadedFileAccess(Path uploadRoot, ObjectMapper mapper) {
        this.uploadRoot = uploadRoot.toAbsolutePath().normalize();
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public OwnedFile authorize(String reference, RequestPrincipal principal) throws IOException {
        if (principal == null) throw new SecurityException("Authenticated upload owner is required");
        if (principal.authenticationType() == RequestPrincipal.AuthenticationType.ANONYMOUS) return resolve(reference);
        return authorize(reference, principal.requireUserId(), principal.tenantId());
    }

    public OwnedFile authorize(String reference, String userId, String tenantId) throws IOException {
        if (userId == null || userId.isBlank()) throw new SecurityException("Trusted upload owner is required");
        var file = resolve(reference);
        if (!userId.equals(file.owner().userId()) || !Objects.equals(tenantId, file.owner().tenantId())) {
            throw new SecurityException("Uploaded file access denied");
        }
        return file;
    }

    private OwnedFile resolve(String reference) throws IOException {
        String fileName;
        if (reference != null && reference.startsWith("/files/input/")) fileName = reference.substring(13);
        else if (reference != null && reference.startsWith("input/")) fileName = reference.substring(6);
        else throw new SecurityException("Invalid uploaded file reference");
        if (!fileName.matches("[a-f0-9-]{36}(?:\\.audio)?(?:\\.[a-z0-9]+)?")) {
            throw new SecurityException("Invalid uploaded file reference");
        }
        try {
            Path base = uploadRoot.toRealPath();
            Path root = uploadRoot.resolve("input").toRealPath();
            Path file = root.resolve(fileName).toRealPath();
            Path metadata = root.resolve(fileName + ".owner.json").toRealPath();
            if (!root.startsWith(base) || !file.startsWith(root) || !metadata.startsWith(root)
                    || !Files.isRegularFile(file)) throw new SecurityException("File path escapes upload root");
            return new OwnedFile(file, mapper.readValue(metadata.toFile(), FileOwner.class));
        } catch (java.nio.file.NoSuchFileException missing) {
            throw new SecurityException("Uploaded file access denied", missing);
        }
    }

    public record FileOwner(String userId, String tenantId, String mimeType) {}
    public record OwnedFile(Path path, FileOwner owner) {}
}

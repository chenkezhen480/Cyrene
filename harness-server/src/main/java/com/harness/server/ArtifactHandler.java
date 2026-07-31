package com.harness.server;

import com.harness.core.model.Artifact;
import com.harness.core.model.ArtifactStore;
import com.harness.server.api.ApiErrorCode;
import com.harness.server.api.ApiResponses;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * HTTP handler for artifact download and preview endpoints.
 */
public class ArtifactHandler {

    private static final Logger log = LoggerFactory.getLogger(ArtifactHandler.class);
    private final ArtifactStore artifactStore;

    public ArtifactHandler(ArtifactStore artifactStore) {
        this.artifactStore = artifactStore;
    }

    /**
     * GET /api/artifacts/{id} — download file (Content-Disposition: attachment)
     */
    public void download(Context ctx) {
        String id = ctx.pathParam("id");
        Optional<Artifact> artifactOpt = artifactStore.get(id);
        if (artifactOpt.isEmpty()) {
            ApiResponses.error(ctx, 404, ApiErrorCode.NOT_FOUND, "Artifact not found: " + id);
            return;
        }
        Artifact artifact = artifactOpt.get();
        serveFile(ctx, artifact, "attachment");
    }

    /**
     * GET /api/artifacts/{id}/preview — inline preview (Content-Disposition: inline)
     */
    public void preview(Context ctx) {
        String id = ctx.pathParam("id");
        Optional<Artifact> artifactOpt = artifactStore.get(id);
        if (artifactOpt.isEmpty()) {
            ApiResponses.error(ctx, 404, ApiErrorCode.NOT_FOUND, "Artifact not found: " + id);
            return;
        }
        Artifact artifact = artifactOpt.get();
        serveFile(ctx, artifact, "inline");
    }

    /**
     * GET /api/artifacts/session/{sessionId} — list all artifacts for a session
     */
    public void listBySession(Context ctx) {
        String sessionId = ctx.pathParam("sessionId");
        ctx.json(artifactStore.listBySession(sessionId));
    }

    private void serveFile(Context ctx, Artifact artifact, String disposition) {
        Path filePath = Path.of(artifact.filePath());
        if (!Files.exists(filePath)) {
            log.warn("Artifact file missing on disk: {}", artifact.filePath());
            ApiResponses.error(
                    ctx, 410, ApiErrorCode.NOT_FOUND, "Artifact file no longer available");
            return;
        }

        ctx.contentType(artifact.mimeType() != null ? artifact.mimeType() : "application/octet-stream");
        // RFC 5987/6266: filename for ASCII, filename*=UTF-8 for non-ASCII (Chinese etc.)
        String safeName = artifact.name().replace("\"", "\\\"").replace("\n", "").replace("\r", "");
        String encodedName = java.net.URLEncoder.encode(artifact.name(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        ctx.header("Content-Disposition",
                disposition + "; filename=\"" + safeName + "\"; filename*=UTF-8''" + encodedName);
        ctx.header("Content-Length", String.valueOf(artifact.sizeBytes()));

        try (OutputStream out = ctx.res().getOutputStream()) {
            Files.copy(filePath, out);
            out.flush();
        } catch (IOException e) {
            log.debug("Failed to serve artifact {}: {}", artifact.id(), e.getMessage());
        }
    }
}

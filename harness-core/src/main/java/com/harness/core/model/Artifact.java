package com.harness.core.model;

import java.time.Instant;

/**
 * Represents a downloadable file artifact produced by a tool (sandbox, image gen, video gen).
 */
public record Artifact(
        String id,           // UUID
        String sessionId,
        String name,         // file name
        ArtifactType type,   // IMAGE, DOCUMENT, CODE, VIDEO, AUDIO, OTHER
        String mimeType,
        long sizeBytes,
        String filePath,     // disk path
        Instant createdAt
) {
    public enum ArtifactType { IMAGE, DOCUMENT, CODE, VIDEO, AUDIO, OTHER }

    public String downloadUrl() { return "/api/artifacts/" + id; }
    public String previewUrl()  { return "/api/artifacts/" + id + "/preview"; }

    /**
     * Infer ArtifactType from MIME type.
     */
    public static ArtifactType inferType(String mimeType) {
        if (mimeType == null) return ArtifactType.OTHER;
        if (mimeType.startsWith("image/"))       return ArtifactType.IMAGE;
        if (mimeType.startsWith("video/"))       return ArtifactType.VIDEO;
        if (mimeType.startsWith("audio/"))       return ArtifactType.AUDIO;
        if (mimeType.startsWith("text/") ||
            mimeType.contains("pdf") ||
            mimeType.contains("word") ||
            mimeType.contains("excel") ||
            mimeType.contains("spreadsheet") ||
            mimeType.contains("presentation") ||
            mimeType.contains("document"))        return ArtifactType.DOCUMENT;
        return ArtifactType.OTHER;
    }
}

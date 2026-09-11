package com.harness.core.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Deterministic identifiers required by the unified knowledge authority model. */
public final class KnowledgeIdentity {

    private KnowledgeIdentity() {
    }

    public static String preferenceConceptId(String tenantId, String userId, String preferenceKey) {
        return sha256("preference"
                + normalizeTenant(tenantId)
                + required(userId, "userId")
                + required(preferenceKey, "preferenceKey"));
    }

    public static String episodeConceptId(
            String tenantId,
            String userId,
            String batchId,
            int proposalIndex
    ) {
        if (proposalIndex < 0) {
            throw new IllegalArgumentException("proposalIndex must not be negative");
        }
        return sha256("episode"
                + normalizeTenant(tenantId)
                + required(userId, "userId")
                + required(batchId, "batchId")
                + proposalIndex);
    }

    public static String playbookConceptId(
            String tenantId,
            String logicalKey
    ) {
        return sha256("playbook"
                + normalizeTenant(tenantId)
                + required(logicalKey, "logicalKey"));
    }

    public static String sourceDocumentConceptId(
            String tenantId,
            String collectionKey,
            String documentId
    ) {
        return sha256("source-document"
                + normalizeTenant(tenantId)
                + required(collectionKey, "collectionKey")
                + required(documentId, "documentId"));
    }

    public static String wikiConceptId(
            String tenantId,
            KnowledgeConceptType conceptType,
            String namespaceKey,
            String logicalKey
    ) {
        if (conceptType == null || conceptType.isMemory()
                || conceptType == KnowledgeConceptType.SOURCE_DOCUMENT) {
            throw new IllegalArgumentException("conceptType must be a compiled Wiki type");
        }
        return sha256("wiki"
                + normalizeTenant(tenantId)
                + conceptType.name()
                + required(namespaceKey, "namespaceKey")
                + required(logicalKey, "logicalKey"));
    }

    public static String documentChunkId(
            String revisionId,
            int chunkIndex,
            String chunkContentHash
    ) {
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must not be negative");
        }
        return sha256(required(revisionId, "revisionId")
                + chunkIndex
                + required(chunkContentHash, "chunkContentHash"));
    }

    public static String artifactId(
            String tenantId,
            String collectionKey,
            KnowledgeArtifactType artifactType,
            String contentHash
    ) {
        if (artifactType == null) {
            throw new IllegalArgumentException("artifactType is required");
        }
        return sha256(normalizeTenant(tenantId)
                + required(collectionKey, "collectionKey")
                + artifactType.name()
                + required(contentHash, "contentHash"));
    }

    public static String revisionId(String conceptId, long revisionNumber, String contentHash) {
        if (revisionNumber < 1) {
            throw new IllegalArgumentException("revisionNumber must be positive");
        }
        return sha256(required(conceptId, "conceptId")
                + revisionNumber
                + required(contentHash, "contentHash"));
    }

    public static String sha256(byte[] content) {
        if (content == null) {
            throw new IllegalArgumentException("content is required");
        }
        return HexFormat.of().formatHex(digest().digest(content));
    }

    public static String sha256(String content) {
        return sha256(required(content, "content").getBytes(StandardCharsets.UTF_8));
    }

    private static String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "" : tenantId.trim();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}

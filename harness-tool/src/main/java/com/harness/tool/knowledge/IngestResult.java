package com.harness.tool.knowledge;

public record IngestResult(
        String jobId,
        String documentId,
        String revisionId,
        String sourceArtifactId,
        String canonicalArtifactId,
        String fileName,
        String collection,
        int chunkCount,
        int embeddingDimension,
        String storedFilePath,
        long ingestDurationMs
) {
}

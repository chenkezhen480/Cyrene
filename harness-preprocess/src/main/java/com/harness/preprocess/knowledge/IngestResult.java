package com.harness.preprocess.knowledge;

public record IngestResult(
        String fileName,
        String collection,
        int chunkCount,
        int embeddingDimension,
        String storedFilePath,
        long ingestDurationMs,
        int repairedBlockCount,
        String repairModel
) {}

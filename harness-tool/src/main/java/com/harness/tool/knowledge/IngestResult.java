package com.harness.tool.knowledge;

import java.util.List;

public record IngestResult(
        String fileName,
        String collection,
        int chunkCount,
        int embeddingDimension,
        String storedFilePath,
        long ingestDurationMs,
        String documentConverter,
        String detectedMimeType,
        String visionModel,
        String visionSource,
        boolean ocrEnabled,
        int visionCalls,
        long conversionDurationMs,
        List<String> conversionWarnings
) {
    public IngestResult {
        conversionWarnings = List.copyOf(conversionWarnings);
    }
}

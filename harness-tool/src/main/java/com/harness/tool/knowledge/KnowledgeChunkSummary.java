package com.harness.tool.knowledge;

import java.util.List;

/**
 * Provider-neutral projection used by knowledge management list APIs.
 * It deliberately excludes chunk content and embeddings.
 */
public record KnowledgeChunkSummary(
        String id,
        String fileName,
        int chunkIndex,
        String documentId,
        List<String> headingPath
) {
    public KnowledgeChunkSummary {
        id = id == null ? "" : id;
        fileName = fileName == null ? "" : fileName;
        documentId = documentId == null ? "" : documentId;
        headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
    }
}

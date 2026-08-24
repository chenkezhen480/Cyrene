package com.harness.tool.knowledge;

import com.harness.tool.rag.RagRetriever;

import java.util.ArrayList;
import java.util.List;

/** Stable model-facing DTO for an explicitly requested document window. */
public record KnowledgeContextData(
        String documentId,
        int anchorChunkIndex,
        List<Chunk> chunks
) {
    public KnowledgeContextData {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }

    public static KnowledgeContextData from(
            String documentId,
            int anchorChunkIndex,
            List<RagRetriever.RagDocument> documents
    ) {
        List<Chunk> chunks = documents == null
                ? List.of()
                : documents.stream().map(Chunk::from).toList();
        return new KnowledgeContextData(documentId, anchorChunkIndex, chunks);
    }

    public record Chunk(
            String chunkId,
            String fileName,
            int chunkIndex,
            List<String> headingPath,
            String content
    ) {
        public Chunk {
            headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
        }

        private static Chunk from(RagRetriever.RagDocument document) {
            return new Chunk(
                    document.id(),
                    document.source(),
                    document.chunkIndex(),
                    stringList(document.metadata().get("heading_path")),
                    document.content());
        }

        private static List<String> stringList(Object value) {
            if (value == null) {
                return List.of();
            }
            if (!(value instanceof List<?> values)) {
                throw new IllegalArgumentException("Knowledge heading_path must be a string list");
            }
            List<String> headings = new ArrayList<>(values.size());
            for (Object item : values) {
                if (!(item instanceof String heading)) {
                    throw new IllegalArgumentException("Knowledge heading_path must be a string list");
                }
                headings.add(heading);
            }
            return List.copyOf(headings);
        }
    }
}

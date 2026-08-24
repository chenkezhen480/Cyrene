package com.harness.tool.knowledge;

import com.harness.tool.rag.RagRetriever;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Stable model-facing knowledge-search DTO. */
public record KnowledgeSearchData(List<Hit> hits) {

    private static final String DOCUMENT_ID_KEY = "document_id";
    private static final String HEADING_PATH_KEY = "heading_path";

    public KnowledgeSearchData {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    public static KnowledgeSearchData from(List<RagRetriever.RagDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return new KnowledgeSearchData(List.of());
        }
        return new KnowledgeSearchData(documents.stream().map(Hit::from).toList());
    }

    public record Hit(
            String chunkId,
            String documentId,
            String fileName,
            Integer chunkIndex,
            List<String> headingPath,
            boolean reingestRequired,
            double score,
            String content
    ) {
        public Hit {
            headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
        }

        private static Hit from(RagRetriever.RagDocument document) {
            Map<String, Object> metadata = document.metadata();
            String documentId = stringValue(metadata.get(DOCUMENT_ID_KEY));
            return new Hit(
                    document.id(),
                    documentId,
                    document.source(),
                    document.chunkIndex() >= 0 ? document.chunkIndex() : null,
                    stringList(metadata.get(HEADING_PATH_KEY)),
                    documentId == null,
                    document.score(),
                    document.content());
        }

        private static String stringValue(Object value) {
            if (value == null) {
                return null;
            }
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException("Knowledge metadata value must be a string");
            }
            return text.isBlank() ? null : text;
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

package com.harness.tool.rag;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RAG 上层契约壳层。
 * 检索编排已上收至上层（KnowledgeDiscoveryRouter + 投影 store / VectorStore），
 * 此类不再承载检索逻辑，仅保留上层 DTO 契约 {@link RagDocument}。
 */
public class RagRetriever {

    public record RagDocument(
            String id,
            String content,
            String source,
            double score,
            Map<String, Object> metadata,
            int chunkIndex
    ) {
        public RagDocument {
            metadata = metadata == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        }

        public RagDocument(String id, String content, String source, double score) {
            this(id, content, source, score, Map.of(), -1);
        }

        public static RagDocument from(VectorStore.Document document) {
            return new RagDocument(
                    document.id(),
                    document.content(),
                    document.source(),
                    document.score(),
                    document.metadata(),
                    document.chunkIndex());
        }
    }
}

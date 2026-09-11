package com.harness.agent.context;

import com.harness.provider.EmbeddingModelProvider;
import com.harness.provider.RerankModelProvider;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.tool.rag.*;
import com.harness.tool.rerank.Reranker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Layer 2: Preprocessing.
 * Orchestrates RAG retrieval and reranking, plus explicit document-window reads.
 */
public class ContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(ContextBuilder.class);

    private final VectorStore vectorStore;
    private final Reranker reranker;

    public ContextBuilder(RerankModelProvider rerankModelProvider,
                          EmbeddingModelProvider embeddingModelProvider) {
        this(VectorStoreFactory.create(embeddingModelProvider), new Reranker(rerankModelProvider));
    }

    ContextBuilder(VectorStore vectorStore, Reranker reranker) {
        this.vectorStore = vectorStore;
        this.reranker = Objects.requireNonNull(reranker, "reranker");
    }

    public ContextResult searchDocumentRevisions(String query, String collection, int limit,
                                                   Map<String, String> documentRevisions) {
        validateSearchScope(collection, limit);
        if (vectorStore == null) throw new IllegalStateException("Knowledge provider is disabled");
        var result = vectorStore.searchDocumentRevisions(collection, query, limit, documentRevisions);
        var documents = result.documents().stream().map(RagRetriever.RagDocument::from).toList();
        var reranked = reranker.rerank(query, documents);
        return new ContextResult(reranked.documents(), Map.of("collection", collection));
    }

    public List<RagRetriever.RagDocument> readContext(
            String collection,
            String documentId,
            String revisionId,
            int anchorChunkIndex,
            int before,
            int after
    ) {
        if (vectorStore == null) {
            throw new IllegalStateException("Knowledge base provider is disabled");
        }
        return vectorStore.readDocumentWindow(
                        collection, documentId, revisionId, anchorChunkIndex, before, after)
                .stream()
                .map(RagRetriever.RagDocument::from)
                .toList();
    }

    public VectorStore vectorStore() {
        return vectorStore;
    }

    public String defaultCollection() {
        return EnvConfig.get().getString(EnvKey.RAG_COLLECTION, "default");
    }

    public int maxSearchLimit() {
        return EnvConfig.get().getInt(EnvKey.RAG_TOP_K, 5);
    }

    private void validateSearchScope(String collection, int limit) {
        if (collection == null || collection.isBlank()) {
            throw new IllegalArgumentException("knowledge collection is required");
        }
        if (limit < 1 || limit > maxSearchLimit()) {
            throw new IllegalArgumentException(
                    "knowledge search limit must be between 1 and " + maxSearchLimit());
        }
    }

    public record ContextResult(
            List<RagRetriever.RagDocument> documents,
            Map<String, String> metadata
    ) {
        public ContextResult {
            documents = documents == null ? List.of() : List.copyOf(documents);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }

        public static ContextResult empty() {
            return new ContextResult(List.of(), Map.of());
        }
    }
}

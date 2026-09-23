package com.harness.agent.context;

import com.harness.core.env.EnvConfig;
import com.harness.core.knowledge.KnowledgeSearchOptions;
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

    public ContextBuilder(VectorStore vectorStore, Reranker reranker) {
        this.vectorStore = vectorStore;
        this.reranker = Objects.requireNonNull(reranker, "reranker");
    }

    public ContextResult searchDocumentRevisions(String query, String collection, int limit,
            Map<String, String> documentRevisions) {
        return searchDocumentRevisions(query, collection, KnowledgeSearchOptions.defaults(limit), documentRevisions);
    }

    public ContextResult searchDocumentRevisions(String query, String collection, KnowledgeSearchOptions options,
            Map<String, String> documentRevisions) {
        if (collection == null || collection.isBlank()) throw new IllegalArgumentException("collection is required");
        if (vectorStore == null) throw new IllegalStateException("Knowledge provider is disabled");
        var documents = vectorStore.searchDocumentRevisions(collection, query, options, documentRevisions)
                .documents().stream().map(RagRetriever.RagDocument::from).toList();
        boolean ranked = options.rerank() && reranker.isAvailable();
        return new ContextResult(ranked ? reranker.rerank(query, documents, options.candidateTopK()).documents() : documents,
                Map.of("collection", collection, "scoreType", ranked ? "documentRerankScore" : "weightedRrfScore"));
    }

    public boolean rerankAvailable() { return reranker.isAvailable(); }

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

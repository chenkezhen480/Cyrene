package com.harness.preprocess.rag;

import com.harness.ai.model.EmbeddingModelProvider;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * RAG (Retrieval-Augmented Generation) retriever.
 * Retrieves relevant documents from a vector store.
 * Configured via HARNESS_RAG_* environment variables.
 */
public class RagRetriever {

    private static final Logger log = LoggerFactory.getLogger(RagRetriever.class);

    private final String provider;
    private final String collection;
    private final int topK;
    private final VectorStore vectorStore;
    private final EmbeddingModelProvider embeddingProvider;

    public RagRetriever() {
        this(null);
    }

    public RagRetriever(EmbeddingModelProvider embeddingProvider) {
        EnvConfig cfg = EnvConfig.get();
        this.provider = cfg.getString(EnvKey.RAG_PROVIDER, "none");
        this.collection = cfg.getString(EnvKey.RAG_COLLECTION, "default");
        this.topK = cfg.getInt(EnvKey.RAG_TOP_K, 5);
        this.vectorStore = VectorStoreFactory.create(embeddingProvider);
        this.embeddingProvider = embeddingProvider;
    }

    /**
     * Retrieve relevant documents for the given query.
     * Returns empty list if RAG is disabled (provider=none).
     */
    public List<RagDocument> retrieve(String query) {
        if ("none".equalsIgnoreCase(provider) || vectorStore == null) {
            return Collections.emptyList();
        }
        if (embeddingProvider == null || !embeddingProvider.isAvailable()) {
            log.warn("RAG retrieve requires an embedding provider. Set HARNESS_MODEL_EMBEDDING_PROVIDER.");
            return Collections.emptyList();
        }

        log.debug("RAG retrieve: provider={}, collection={}, topK={}, query={}", provider, collection, topK, query);

        try {
            float[] embedding = embeddingProvider.embed(query).vector();
            List<VectorStore.Document> docs = vectorStore.searchVector(collection, embedding, topK);
            return docs.stream()
                    .map(d -> new RagDocument(d.id(), d.content(), d.source(), d.score()))
                    .toList();
        } catch (Exception e) {
            log.warn("RAG retrieve failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get the vector store for direct operations.
     */
    public VectorStore getVectorStore() {
        return vectorStore;
    }

    /**
     * Get the PgVectorStore if the provider is pgvector (for chunk linking operations).
     * Returns null if provider is not pgvector.
     */
    public PgVectorStore getPgVectorStore() {
        if (vectorStore instanceof PgVectorStore pg) {
            return pg;
        }
        return null;
    }

    public String getProvider() {
        return provider;
    }

    public record RagDocument(
            String id,
            String content,
            String source,
            double score
    ) {}
}

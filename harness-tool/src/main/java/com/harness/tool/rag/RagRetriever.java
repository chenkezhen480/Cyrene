package com.harness.tool.rag;

import com.harness.provider.EmbeddingModelProvider;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            throw new IllegalStateException(
                    "RAG retrieve requires an embedding provider. Set "
                            + EnvKey.MODEL_EMBEDDING_PROVIDER + ".");
        }

        log.debug("RAG retrieve: provider={}, collection={}, topK={}, query={}", provider, collection, topK, query);

        float[] embedding = embeddingProvider.embed(query).vector();
        List<VectorStore.Document> docs = vectorStore.searchVector(collection, embedding, topK);
        return docs.stream()
                .map(RagDocument::from)
                .toList();
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

package com.harness.preprocess.rag;

import com.harness.ai.model.EmbeddingModelProvider;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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
    private final String url;
    private final String apiKey;
    private final String collection;
    private final int topK;
    private final double scoreThreshold;
    private final PgVectorRagRetriever pgVectorRetriever;

    public RagRetriever() {
        this(null);
    }

    public RagRetriever(EmbeddingModelProvider embeddingProvider) {
        EnvConfig cfg = EnvConfig.get();
        this.provider = cfg.getString(EnvKey.RAG_PROVIDER, "none");
        this.url = cfg.getString(EnvKey.RAG_URL, "");
        this.apiKey = cfg.getString(EnvKey.RAG_API_KEY, "");
        this.collection = cfg.getString(EnvKey.RAG_COLLECTION, "default");
        this.topK = cfg.getInt(EnvKey.RAG_TOP_K, 5);
        this.scoreThreshold = cfg.getDouble(EnvKey.RAG_SCORE_THRESHOLD, 0.7);
        this.pgVectorRetriever = "pgvector".equalsIgnoreCase(provider)
                ? new PgVectorRagRetriever(embeddingProvider) : null;
    }

    /**
     * Retrieve relevant documents for the given query.
     * Returns empty list if RAG is disabled (provider=none).
     */
    public List<RagDocument> retrieve(String query) {
        if ("none".equalsIgnoreCase(provider)) {
            return Collections.emptyList();
        }

        log.debug("RAG retrieve: provider={}, collection={}, topK={}, query={}", provider, collection, topK, query);

        return switch (provider.toLowerCase()) {
            case "qdrant" -> retrieveFromQdrant(query);
            case "elasticsearch" -> retrieveFromElasticsearch(query);
            case "pinecone" -> retrieveFromPinecone(query);
            case "pgvector" -> retrieveFromPgVector(query);
            case "local" -> retrieveFromLocal(query);
            default -> {
                log.warn("Unknown RAG provider: {}, returning empty results", provider);
                yield Collections.emptyList();
            }
        };
    }

    private List<RagDocument> retrieveFromPgVector(String query) {
        if (pgVectorRetriever == null) {
            log.warn("pgvector retriever not initialized");
            return Collections.emptyList();
        }
        return pgVectorRetriever.retrieveByText(query);
    }

    private List<RagDocument> retrieveFromQdrant(String query) {
        // TODO: Implement Qdrant client via HTTP API
        // POST {url}/collections/{collection}/points/search
        log.info("Qdrant RAG not yet implemented, returning empty");
        return Collections.emptyList();
    }

    private List<RagDocument> retrieveFromElasticsearch(String query) {
        // TODO: Implement Elasticsearch client
        log.info("Elasticsearch RAG not yet implemented, returning empty");
        return Collections.emptyList();
    }

    private List<RagDocument> retrieveFromPinecone(String query) {
        // TODO: Implement Pinecone client
        log.info("Pinecone RAG not yet implemented, returning empty");
        return Collections.emptyList();
    }

    private List<RagDocument> retrieveFromLocal(String query) {
        // TODO: Implement local vector store (e.g., using ONNX embeddings + HNSW)
        log.info("Local RAG not yet implemented, returning empty");
        return Collections.emptyList();
    }

    /**
     * Get the pgvector retriever for direct operations (insert, delete, etc.).
     * Returns null if provider is not pgvector.
     */
    public PgVectorRagRetriever getPgVectorRetriever() {
        return pgVectorRetriever;
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

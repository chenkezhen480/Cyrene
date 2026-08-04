package com.harness.provider;

import java.util.List;

/**
 * 5. Rerank Model Provider
 * Handles: scoring and reordering RAG retrieval results.
 * Typically wraps a cross-encoder model (BGE-Reranker, Cohere Rerank, etc.)
 */
public interface RerankModelProvider {

    /**
     * Score a query-document pair.
     *
     * @param query    the search query
     * @param document the document text
     * @return relevance score (higher = more relevant)
     */
    double score(String query, String document);

    /**
     * Rerank a list of documents by relevance to the query.
     *
     * @param query     the search query
     * @param documents list of document texts
     * @return reranked indices (most relevant first)
     */
    List<RankedResult> rerank(String query, List<String> documents, int topN);

    /**
     * Check if this provider is available.
     */
    boolean isAvailable();

    String providerName();
    String modelName();

    record RankedResult(int index, String document, double score) {}
}

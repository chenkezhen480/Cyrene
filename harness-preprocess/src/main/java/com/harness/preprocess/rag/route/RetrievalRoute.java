package com.harness.preprocess.rag.route;

import com.harness.preprocess.rag.RagRetriever;

import java.util.List;

/**
 * A single retrieval route (vector, fulltext, knowledge graph, etc.).
 * Each route is self-contained and can execute independently.
 */
public interface RetrievalRoute {

    /**
     * Retrieve documents for the given query.
     *
     * @param query the search query (may be a rewritten query)
     * @return matched documents, sorted by relevance descending
     */
    List<RagRetriever.RagDocument> retrieve(String query);

    /**
     * Route identifier for logging and metadata.
     */
    String routeName();

    /**
     * Whether this route is currently available and configured.
     */
    boolean isAvailable();
}

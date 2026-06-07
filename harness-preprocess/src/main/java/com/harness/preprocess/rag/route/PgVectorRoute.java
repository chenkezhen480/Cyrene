package com.harness.preprocess.rag.route;

import com.harness.preprocess.rag.PgVectorRagRetriever;
import com.harness.preprocess.rag.RagRetriever;

import java.util.List;

/**
 * Adapter wrapping the existing PgVectorRagRetriever as a RetrievalRoute.
 */
public class PgVectorRoute implements RetrievalRoute {

    private final PgVectorRagRetriever retriever;

    public PgVectorRoute(PgVectorRagRetriever retriever) {
        this.retriever = retriever;
    }

    @Override
    public List<RagRetriever.RagDocument> retrieve(String query) {
        return retriever.retrieveByText(query);
    }

    @Override
    public String routeName() {
        return "pgvector";
    }

    @Override
    public boolean isAvailable() {
        return retriever != null;
    }

    public PgVectorRagRetriever getRetriever() {
        return retriever;
    }
}

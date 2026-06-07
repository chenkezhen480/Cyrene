package com.harness.preprocess.rag.route;

import com.harness.preprocess.rag.RagRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Knowledge graph retrieval route (future stub).
 * Reserved for graph-based retrieval when a knowledge graph backend is integrated.
 */
public class KnowledgeGraphRoute implements RetrievalRoute {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphRoute.class);

    @Override
    public List<RagRetriever.RagDocument> retrieve(String query) {
        log.warn("[KnowledgeGraphRoute] Not yet implemented, returning empty results");
        return List.of();
    }

    @Override
    public String routeName() {
        return "knowledge-graph";
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}

package com.harness.preprocess.rag.route;

import com.harness.ai.model.EmbeddingModelProvider;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.preprocess.rag.PgVectorRagRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Factory for creating enabled retrieval routes based on env config.
 */
public final class RetrievalRouteFactory {

    private static final Logger log = LoggerFactory.getLogger(RetrievalRouteFactory.class);

    private RetrievalRouteFactory() {}

    /**
     * Create all enabled retrieval routes.
     *
     * @param embeddingProvider embedding model (used by pgvector route)
     * @return list of enabled and available routes
     */
    public static List<RetrievalRoute> createEnabledRoutes(EmbeddingModelProvider embeddingProvider) {
        EnvConfig cfg = EnvConfig.get();
        List<RetrievalRoute> routes = new ArrayList<>();

        // pgvector route (enabled when HARNESS_RAG_PROVIDER=pgvector)
        String provider = cfg.getString(EnvKey.RAG_PROVIDER, "none");
        if ("pgvector".equalsIgnoreCase(provider)) {
            PgVectorRagRetriever pgRetriever = new PgVectorRagRetriever(embeddingProvider);
            routes.add(new PgVectorRoute(pgRetriever));
        }

        // fulltext route
        if (cfg.getBool(EnvKey.RAG_FULLTEXT_ENABLED, false)) {
            routes.add(new FulltextRoute());
        }

        // knowledge graph stub
        if (cfg.getBool(EnvKey.RAG_KNOWLEDGE_GRAPH_ENABLED, false)) {
            routes.add(new KnowledgeGraphRoute());
        }

        log.info("[RetrievalRoute] Enabled routes: {}", routes.stream()
                .map(RetrievalRoute::routeName).toList());
        return routes;
    }

    /**
     * Extract PgVectorRagRetriever from the routes list (for SemanticContextRetriever wiring).
     */
    public static PgVectorRagRetriever findPgVectorRetriever(List<RetrievalRoute> routes) {
        return routes.stream()
                .filter(r -> r instanceof PgVectorRoute)
                .map(r -> ((PgVectorRoute) r).getRetriever())
                .findFirst()
                .orElse(null);
    }
}

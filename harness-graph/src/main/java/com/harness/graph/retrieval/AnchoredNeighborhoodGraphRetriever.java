package com.harness.graph.retrieval;

import com.harness.core.model.GraphRequestContext;
import com.harness.graph.config.GraphSettings;
import com.harness.graph.model.GraphNeighborhoodRequest;
import com.harness.graph.model.GraphRouteResult;
import com.harness.graph.schema.GraphSchemaDefinition;
import com.harness.graph.schema.GraphSchemaRegistry;
import com.harness.graph.store.KnowledgeGraphStore;

import java.util.Objects;
import java.util.Set;

/**
 * Deterministic neighborhood retrieval anchored to server-provided subject IDs.
 */
public final class AnchoredNeighborhoodGraphRetriever implements GraphKnowledgeRetriever {

    public static final String QUERY_ID = "anchored-neighborhood";

    private final KnowledgeGraphStore graphStore;
    private final GraphSchemaRegistry schemaRegistry;
    private final GraphSettings settings;

    public AnchoredNeighborhoodGraphRetriever(
            KnowledgeGraphStore graphStore,
            GraphSchemaRegistry schemaRegistry,
            GraphSettings settings
    ) {
        this.graphStore = Objects.requireNonNull(graphStore, "graphStore");
        this.schemaRegistry = Objects.requireNonNull(schemaRegistry, "schemaRegistry");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public GraphRouteResult retrieve(
            GraphRequestContext requestContext,
            String queryId,
            Set<String> relationTypes,
            int maxDepth,
            int limit
    ) {
        Objects.requireNonNull(requestContext, "requestContext");
        String effectiveQueryId = queryId == null || queryId.isBlank() ? QUERY_ID : queryId;
        requestContext.requireAllowedQuery(effectiveQueryId);
        if (!QUERY_ID.equals(effectiveQueryId)) {
            throw new IllegalArgumentException("Unsupported graph query strategy: " + effectiveQueryId);
        }

        GraphSchemaDefinition schema = schemaRegistry.require(requestContext.schemaId());
        int effectiveDepth = maxDepth <= 0 ? schema.defaultMaxDepth() : maxDepth;
        int effectiveLimit = limit <= 0 ? settings.defaultLimit() : limit;
        return graphStore.findNeighborhood(new GraphNeighborhoodRequest(
                requestContext.graphId(),
                requestContext.schemaId(),
                requestContext.subjectIds(),
                relationTypes == null ? Set.of() : relationTypes,
                effectiveDepth,
                effectiveLimit
        ));
    }
}

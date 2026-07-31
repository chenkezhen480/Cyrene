package com.harness.server;

import com.harness.core.model.PageResponse;
import com.harness.graph.config.GraphSettings;
import com.harness.graph.model.GraphDeleteMode;
import com.harness.graph.model.GraphDeleteRequest;
import com.harness.graph.model.GraphDeleteTarget;
import com.harness.graph.model.GraphMutationBatch;
import com.harness.graph.model.GraphNeighborhoodRequest;
import com.harness.graph.model.GraphNode;
import com.harness.graph.model.GraphNodeKey;
import com.harness.graph.model.GraphNodePageRequest;
import com.harness.graph.model.GraphRelation;
import com.harness.graph.model.GraphRelationPageRequest;
import com.harness.graph.model.GraphSpacePageRequest;
import com.harness.graph.schema.GraphSchemaDefinition;
import com.harness.graph.schema.GraphSchemaRegistry;
import com.harness.graph.store.KnowledgeGraphStore;
import io.javalin.http.Context;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * Transactional and graph-space-scoped HTTP management API for structured graph data.
 */
public final class GraphManagementHandler {

    private final KnowledgeGraphStore graphStore;
    private final GraphSchemaRegistry schemaRegistry;
    private final GraphSettings settings;
    private final GraphRequestExecutor requestExecutor;

    public GraphManagementHandler(
            KnowledgeGraphStore graphStore,
            GraphSchemaRegistry schemaRegistry,
            GraphSettings settings
    ) {
        this(graphStore, schemaRegistry, settings,
                new GraphRequestExecutor(new GraphRequestAuthenticator()));
    }

    GraphManagementHandler(
            KnowledgeGraphStore graphStore,
            GraphSchemaRegistry schemaRegistry,
            GraphSettings settings,
            GraphRequestAuthenticator requestAuthenticator
    ) {
        this(graphStore, schemaRegistry, settings, new GraphRequestExecutor(requestAuthenticator));
    }

    GraphManagementHandler(
            KnowledgeGraphStore graphStore,
            GraphSchemaRegistry schemaRegistry,
            GraphSettings settings,
            GraphRequestExecutor requestExecutor
    ) {
        this.graphStore = Objects.requireNonNull(graphStore, "graphStore");
        this.schemaRegistry = Objects.requireNonNull(schemaRegistry, "schemaRegistry");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.requestExecutor = Objects.requireNonNull(requestExecutor, "requestExecutor");
    }

    public void status(Context context) {
        context.json(Map.of(
                "provider", graphStore.providerName(),
                "enabled", !"none".equals(graphStore.providerName()),
                "schemaCount", schemaRegistry.list().size()
        ));
    }

    public void listSchemas(Context context) {
        execute(context, () -> {
            int limit = requestedLimit(context);
            String cursor = optionalQuery(context, "cursor");
            List<GraphSchemaDefinition> fetched = schemaRegistry.list().stream()
                    .filter(schema -> cursor.isBlank() || schema.schemaId().compareTo(cursor) > 0)
                    .limit((long) limit + 1)
                    .toList();
            context.json(PageResponse.fromFetched(
                    fetched, limit, GraphSchemaDefinition::schemaId));
        });
    }

    public void getSchema(Context context) {
        execute(context, () -> {
            context.json(schemaRegistry.require(context.pathParam("schemaId")));
        });
    }

    public void mutate(Context context) {
        execute(context, () -> {
            GraphMutationBatch request = context.bodyAsClass(GraphMutationBatch.class);
            context.json(graphStore.upsertBatch(request));
        });
    }

    public void upsertNodes(Context context) {
        execute(context, () -> {
            GraphNodeBatchRequest request = context.bodyAsClass(GraphNodeBatchRequest.class);
            context.json(graphStore.upsertBatch(new GraphMutationBatch(
                    request.requestId(), request.graphId(), request.schemaId(), request.nodes(), List.of())));
        });
    }

    public void upsertRelations(Context context) {
        execute(context, () -> {
            GraphRelationBatchRequest request = context.bodyAsClass(GraphRelationBatchRequest.class);
            context.json(graphStore.upsertBatch(new GraphMutationBatch(
                    request.requestId(), request.graphId(), request.schemaId(), List.of(), request.relations())));
        });
    }

    public void getNode(Context context) {
        execute(context, () -> {
            String schemaId = requiredQuery(context, "schemaId");
            GraphNode node = graphStore.getNode(new GraphNodeKey(
                    requiredQuery(context, "graphId"), schemaId, context.pathParam("nodeId")));
            if (node == null) {
                throw new NoSuchElementException("Graph node was not found");
            }
            context.json(node);
        });
    }

    public void listNodes(Context context) {
        execute(context, () -> {
            context.json(graphStore.listNodes(new GraphNodePageRequest(
                    requiredQuery(context, "graphId"),
                    requiredQuery(context, "schemaId"),
                    optionalQuery(context, "label"),
                    optionalQuery(context, "name"),
                    requestedLimit(context),
                    optionalQuery(context, "cursor")
            )));
        });
    }

    public void listRelations(Context context) {
        execute(context, () -> {
            context.json(graphStore.listRelations(new GraphRelationPageRequest(
                    requiredQuery(context, "graphId"),
                    requiredQuery(context, "schemaId"),
                    optionalQuery(context, "relationType"),
                    requestedLimit(context),
                    optionalQuery(context, "cursor")
            )));
        });
    }

    public void listGraphSpaces(Context context) {
        execute(context, () -> {
            context.json(graphStore.listGraphSpaces(new GraphSpacePageRequest(
                    requestedLimit(context),
                    optionalQuery(context, "cursor")
            )));
        });
    }

    public void query(Context context) {
        execute(context, () -> {
            GraphQueryRequest request = context.bodyAsClass(GraphQueryRequest.class);
            context.json(graphStore.findNeighborhood(new GraphNeighborhoodRequest(
                    request.graphId(),
                    request.schemaId(),
                    request.subjectIds(),
                    request.relationTypes(),
                    request.maxDepth() <= 0 ? settings.defaultMaxDepth() : request.maxDepth(),
                    request.limit() <= 0 ? settings.defaultLimit() : request.limit()
            )));
        });
    }

    public void deleteNode(Context context) {
        delete(context, GraphDeleteTarget.NODE, GraphDeleteMode.REJECT_IF_REFERENCED);
    }

    public void deleteRelation(Context context) {
        delete(context, GraphDeleteTarget.RELATION, GraphDeleteMode.REJECT_IF_REFERENCED);
    }

    public void deleteSource(Context context) {
        delete(context, GraphDeleteTarget.SOURCE, GraphDeleteMode.DELETE_DERIVED_ONLY);
    }

    private void delete(Context context, GraphDeleteTarget target, GraphDeleteMode defaultMode) {
        execute(context, () -> {
            String requestedMode = optionalQuery(context, "mode");
            GraphDeleteMode mode = requestedMode.isBlank()
                    ? defaultMode
                    : GraphDeleteMode.valueOf(requestedMode.toUpperCase());
            context.json(graphStore.delete(new GraphDeleteRequest(
                    requiredQuery(context, "graphId"),
                    requiredQuery(context, "schemaId"),
                    target,
                    context.pathParam(target == GraphDeleteTarget.NODE
                            ? "nodeId"
                            : target == GraphDeleteTarget.RELATION ? "relationId" : "sourceId"),
                    mode
            )));
        });
    }

    private int requestedLimit(Context context) {
        return ApiRequestParameters.limit(
                context, settings.defaultLimit(), settings.maxLimit());
    }

    private static String requiredQuery(Context context, String name) {
        return ApiRequestParameters.requiredQuery(context, name);
    }

    private static String optionalQuery(Context context, String name) {
        return ApiRequestParameters.optionalQuery(context, name);
    }

    private void execute(Context context, HandlerAction action) {
        requestExecutor.execute(context, action::run);
    }

    @FunctionalInterface
    private interface HandlerAction {
        void run();
    }

    public record GraphNodeBatchRequest(
            String requestId,
            String graphId,
            String schemaId,
            List<GraphNode> nodes
    ) {
    }

    public record GraphRelationBatchRequest(
            String requestId,
            String graphId,
            String schemaId,
            List<GraphRelation> relations
    ) {
    }

    public record GraphQueryRequest(
            String graphId,
            String schemaId,
            Set<String> subjectIds,
            Set<String> relationTypes,
            int maxDepth,
            int limit
    ) {
    }
}

package com.harness.server;

import com.harness.core.model.PageResponse;
import com.harness.graph.config.GraphSettings;
import com.harness.graph.build.GraphMutationCommitter;
import com.harness.graph.model.GraphChangeSet;
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
import java.util.Locale;
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
    private final GraphMutationCommitter mutationCommitter;
    private final GraphDeletionService deletionService;

    public GraphManagementHandler(
            KnowledgeGraphStore graphStore,
            GraphSchemaRegistry schemaRegistry,
            GraphSettings settings,
            GraphMutationCommitter mutationCommitter,
            GraphDeletionService deletionService
    ) {
        this(graphStore, schemaRegistry, settings,
                new GraphRequestExecutor(new GraphRequestAuthenticator()),
                mutationCommitter, deletionService);
    }

    GraphManagementHandler(
            KnowledgeGraphStore graphStore,
            GraphSchemaRegistry schemaRegistry,
            GraphSettings settings,
            GraphRequestAuthenticator requestAuthenticator,
            GraphDeletionService deletionService
    ) {
        this(graphStore, schemaRegistry, settings,
                new GraphRequestExecutor(requestAuthenticator),
                unavailableCommitter(), deletionService);
    }

    GraphManagementHandler(
            KnowledgeGraphStore graphStore,
            GraphSchemaRegistry schemaRegistry,
            GraphSettings settings,
            GraphRequestExecutor requestExecutor,
            GraphMutationCommitter mutationCommitter,
            GraphDeletionService deletionService
    ) {
        this.graphStore = Objects.requireNonNull(graphStore, "graphStore");
        this.schemaRegistry = Objects.requireNonNull(schemaRegistry, "schemaRegistry");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.requestExecutor = Objects.requireNonNull(requestExecutor, "requestExecutor");
        this.mutationCommitter = Objects.requireNonNull(
                mutationCommitter, "mutationCommitter");
        this.deletionService = Objects.requireNonNull(deletionService, "deletionService");
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
            context.json(mutationCommitter.commit(new GraphChangeSet(
                    request.requestId(), request.graphId(), request.schemaId(),
                    request.nodes(), request.relations(), Set.of(), Set.of())));
        });
    }

    public void upsertNodes(Context context) {
        execute(context, () -> {
            GraphNodeBatchRequest request = context.bodyAsClass(GraphNodeBatchRequest.class);
            context.json(mutationCommitter.commit(new GraphChangeSet(
                    request.requestId(), request.graphId(), request.schemaId(),
                    request.nodes(), List.of(), Set.of(), Set.of())));
        });
    }

    public void upsertRelations(Context context) {
        execute(context, () -> {
            GraphRelationBatchRequest request = context.bodyAsClass(GraphRelationBatchRequest.class);
            context.json(mutationCommitter.commit(new GraphChangeSet(
                    request.requestId(), request.graphId(), request.schemaId(),
                    List.of(), request.relations(), Set.of(), Set.of())));
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

    public void deleteGraphSpace(Context context) {
        execute(context, () -> context.json(deletionService.deleteSpace(
                requiredQuery(context, "graphId"),
                requiredQuery(context, "schemaId"))));
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
        deleteTarget(context, GraphDeleteTarget.NODE, context.pathParam("nodeId"));
    }

    public void deleteRelation(Context context) {
        deleteTarget(context, GraphDeleteTarget.RELATION, context.pathParam("relationId"));
    }

    public void deleteSource(Context context) {
        deleteTarget(context, GraphDeleteTarget.SOURCE, context.pathParam("sourceId"));
    }

    private void deleteTarget(Context context, GraphDeleteTarget target, String targetId) {
        execute(context, () -> {
            // The NoOp store answers a disabled provider with a store exception, which would surface
            // as a server fault; a disabled capability is a conflict, not a failure.
            GraphDeletionService.requireGraphProvider(graphStore);
            context.json(graphStore.delete(new GraphDeleteRequest(
                    requiredQuery(context, "graphId"),
                    requiredQuery(context, "schemaId"),
                    target,
                    targetId,
                    deleteMode(context, target)
            )));
        });
    }

    /**
     * Resolves the deletion policy. A single item is removed only when it is unreferenced unless the
     * caller asks for {@code DETACH}; a source deletion always removes its derived rows instead.
     *
     * <p>The store rejects the incompatible target/mode combinations, so they are refused here as
     * invalid requests rather than surfacing as an operation failure.</p>
     */
    private static GraphDeleteMode deleteMode(Context context, GraphDeleteTarget target) {
        String requested = optionalQuery(context, "mode").trim();
        if (requested.isEmpty()) {
            return target == GraphDeleteTarget.SOURCE
                    ? GraphDeleteMode.DELETE_DERIVED_ONLY
                    : GraphDeleteMode.REJECT_IF_REFERENCED;
        }
        GraphDeleteMode mode;
        try {
            mode = GraphDeleteMode.valueOf(requested.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Unknown graph delete mode: " + requested);
        }
        if (target == GraphDeleteTarget.SOURCE && mode != GraphDeleteMode.DELETE_DERIVED_ONLY) {
            throw new IllegalArgumentException(
                    "SOURCE deletion requires mode=DELETE_DERIVED_ONLY");
        }
        if (target != GraphDeleteTarget.SOURCE && mode == GraphDeleteMode.DELETE_DERIVED_ONLY) {
            throw new IllegalArgumentException(
                    "mode=DELETE_DERIVED_ONLY is only valid for SOURCE deletion");
        }
        return mode;
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

    private static GraphMutationCommitter unavailableCommitter() {
        return ignored -> {
            throw new IllegalStateException("Graph Mutation Saga is not configured");
        };
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

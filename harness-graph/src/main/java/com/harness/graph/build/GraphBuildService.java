package com.harness.graph.build;

import com.harness.graph.model.GraphChangeSet;
import com.harness.graph.model.GraphMutationBatch;
import com.harness.graph.model.GraphMutationResult;
import com.harness.graph.store.KnowledgeGraphStore;

import java.util.Objects;

public final class GraphBuildService {

    private final KnowledgeGraphStore graphStore;
    private final GraphDataConverterRegistry converterRegistry;

    public GraphBuildService(
            KnowledgeGraphStore graphStore,
            GraphDataConverterRegistry converterRegistry
    ) {
        this.graphStore = Objects.requireNonNull(graphStore, "graphStore");
        this.converterRegistry = Objects.requireNonNull(converterRegistry, "converterRegistry");
    }

    public GraphBuildResult build(GraphBuildRequest request) {
        requireRequest(request);
        if (request.sourceType() != GraphBuildSourceType.STRUCTURED) {
            throw new IllegalArgumentException(
                    "Natural-language graph data must be previewed and confirmed as structured data before build");
        }
        GraphMutationDraft draft = convert(request);
        if (draft.isEmpty() && !request.hasDeletions()) {
            throw new IllegalArgumentException("Graph build must contain at least one change");
        }
        GraphMutationResult mutationResult = request.hasDeletions()
                ? graphStore.applyChanges(new GraphChangeSet(
                        request.requestId(),
                        request.graphId(),
                        request.schemaId(),
                        draft.nodes(),
                        draft.relations(),
                        request.deleteNodeIds(),
                        request.deleteRelationIds()
                ))
                : graphStore.upsertBatch(new GraphMutationBatch(
                        request.requestId(),
                        request.graphId(),
                        request.schemaId(),
                        draft.nodes(),
                        draft.relations()
                ));
        return new GraphBuildResult(
                request.requestId(),
                request.graphId(),
                request.schemaId(),
                request.sourceType(),
                request.converterId(),
                mutationResult.committed(),
                mutationResult.nodeCount(),
                mutationResult.relationCount()
        );
    }

    public GraphBuildPreviewResult preview(GraphBuildRequest request) {
        requireRequest(request);
        if (request.sourceType() != GraphBuildSourceType.NATURAL_LANGUAGE) {
            throw new IllegalArgumentException("Graph preview only accepts natural-language sources");
        }
        GraphMutationDraft draft = convert(request);
        if (draft.isEmpty()) {
            throw new IllegalArgumentException(
                    "Graph conversion produced no nodes or relations");
        }
        return new GraphBuildPreviewResult(
                request.requestId(),
                request.graphId(),
                request.schemaId(),
                request.sourceType(),
                request.converterId(),
                draft.nodes(),
                draft.relations()
        );
    }

    private GraphMutationDraft convert(GraphBuildRequest request) {
        GraphDataConverter converter = converterRegistry.require(
                request.sourceType(), request.converterId());
        return converter.convert(request);
    }

    private static void requireRequest(GraphBuildRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
    }
}

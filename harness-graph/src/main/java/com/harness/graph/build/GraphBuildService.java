package com.harness.graph.build;

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
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        GraphDataConverter converter = converterRegistry.require(
                request.sourceType(), request.converterId());
        GraphMutationDraft draft = converter.convert(request);
        GraphMutationResult mutationResult = graphStore.upsertBatch(new GraphMutationBatch(
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
}

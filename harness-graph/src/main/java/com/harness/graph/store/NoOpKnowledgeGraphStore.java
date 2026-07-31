package com.harness.graph.store;

import com.harness.core.model.PageInfo;
import com.harness.core.model.PageResponse;
import com.harness.graph.model.GraphDeleteRequest;
import com.harness.graph.model.GraphDeleteResult;
import com.harness.graph.model.GraphMutationBatch;
import com.harness.graph.model.GraphMutationResult;
import com.harness.graph.model.GraphNeighborhoodRequest;
import com.harness.graph.model.GraphNode;
import com.harness.graph.model.GraphNodeKey;
import com.harness.graph.model.GraphNodePageRequest;
import com.harness.graph.model.GraphRelation;
import com.harness.graph.model.GraphRelationPageRequest;
import com.harness.graph.model.GraphRouteResult;
import com.harness.graph.model.GraphSpacePageRequest;
import com.harness.graph.model.GraphSpaceSummary;

import java.util.List;

public final class NoOpKnowledgeGraphStore implements KnowledgeGraphStore {

    @Override
    public GraphMutationResult upsertBatch(GraphMutationBatch mutationBatch) {
        throw new GraphStoreException("Knowledge graph provider is disabled");
    }

    @Override
    public GraphNode getNode(GraphNodeKey nodeKey) {
        return null;
    }

    @Override
    public PageResponse<GraphNode> listNodes(GraphNodePageRequest request) {
        return new PageResponse<>(List.of(), new PageInfo(request.limit(), "", false));
    }

    @Override
    public PageResponse<GraphRelation> listRelations(GraphRelationPageRequest request) {
        return new PageResponse<>(List.of(), new PageInfo(request.limit(), "", false));
    }

    @Override
    public PageResponse<GraphSpaceSummary> listGraphSpaces(GraphSpacePageRequest request) {
        return new PageResponse<>(List.of(), new PageInfo(request.limit(), "", false));
    }

    @Override
    public GraphRouteResult findNeighborhood(GraphNeighborhoodRequest request) {
        return GraphRouteResult.empty();
    }

    @Override
    public GraphDeleteResult delete(GraphDeleteRequest request) {
        throw new GraphStoreException("Knowledge graph provider is disabled");
    }

    @Override
    public String providerName() {
        return "none";
    }
}

package com.harness.graph.store;

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

public interface KnowledgeGraphStore extends AutoCloseable {

    GraphMutationResult upsertBatch(GraphMutationBatch mutationBatch);

    GraphNode getNode(GraphNodeKey nodeKey);

    PageResponse<GraphNode> listNodes(GraphNodePageRequest request);

    PageResponse<GraphRelation> listRelations(GraphRelationPageRequest request);

    PageResponse<GraphSpaceSummary> listGraphSpaces(GraphSpacePageRequest request);

    GraphRouteResult findNeighborhood(GraphNeighborhoodRequest request);

    GraphDeleteResult delete(GraphDeleteRequest request);

    String providerName();

    @Override
    default void close() {
    }
}

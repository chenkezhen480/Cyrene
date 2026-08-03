package com.harness.graph.store;

import com.harness.core.model.PageResponse;
import com.harness.graph.model.GraphDeleteRequest;
import com.harness.graph.model.GraphDeleteResult;
import com.harness.graph.model.GraphChangeSet;
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
import com.harness.graph.model.GraphSpaceKey;
import com.harness.graph.model.GraphSpaceSummary;

public interface KnowledgeGraphStore extends AutoCloseable {

    GraphMutationResult upsertBatch(GraphMutationBatch mutationBatch);

    default GraphMutationResult applyChanges(GraphChangeSet changeSet) {
        if (!changeSet.deleteNodeIds().isEmpty() || !changeSet.deleteRelationIds().isEmpty()) {
            throw new UnsupportedOperationException(
                    "The knowledge graph store does not support transactional deletions");
        }
        return upsertBatch(new GraphMutationBatch(
                changeSet.requestId(),
                changeSet.graphId(),
                changeSet.schemaId(),
                changeSet.nodes(),
                changeSet.relations()
        ));
    }

    GraphNode getNode(GraphNodeKey nodeKey);

    PageResponse<GraphNode> listNodes(GraphNodePageRequest request);

    PageResponse<GraphRelation> listRelations(GraphRelationPageRequest request);

    PageResponse<GraphSpaceSummary> listGraphSpaces(GraphSpacePageRequest request);

    boolean hasGraphSpacesForSchema(String schemaId);

    GraphDeleteResult deleteGraphSpace(GraphSpaceKey graphSpaceKey);

    GraphRouteResult findNeighborhood(GraphNeighborhoodRequest request);

    GraphDeleteResult delete(GraphDeleteRequest request);

    String providerName();

    @Override
    default void close() {
    }
}

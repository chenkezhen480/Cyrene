package com.harness.graph.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One transactional graph-space change containing upserts and explicit deletions.
 */
public record GraphChangeSet(
        String requestId,
        String graphId,
        String schemaId,
        List<GraphNode> nodes,
        List<GraphRelation> relations,
        Set<String> deleteNodeIds,
        Set<String> deleteRelationIds
) {
    public GraphChangeSet {
        requestId = GraphModelSupport.requireText(requestId, "requestId");
        graphId = GraphModelSupport.requireText(graphId, "graphId");
        schemaId = GraphModelSupport.requireText(schemaId, "schemaId");
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        relations = relations == null ? List.of() : List.copyOf(relations);
        deleteNodeIds = copyIds(deleteNodeIds, "deleteNodeId");
        deleteRelationIds = copyIds(deleteRelationIds, "deleteRelationId");
        if (nodes.isEmpty() && relations.isEmpty()
                && deleteNodeIds.isEmpty() && deleteRelationIds.isEmpty()) {
            throw new IllegalArgumentException("graph change set must contain at least one change");
        }

        Set<String> upsertNodeIds = new LinkedHashSet<>();
        for (GraphNode node : nodes) {
            upsertNodeIds.add(node.nodeId());
        }
        Set<String> upsertRelationIds = new LinkedHashSet<>();
        for (GraphRelation relation : relations) {
            upsertRelationIds.add(relation.relationId());
            if (deleteNodeIds.contains(relation.sourceNodeId())
                    || deleteNodeIds.contains(relation.targetNodeId())) {
                throw new IllegalArgumentException(
                        "Relations cannot reference a node deleted in the same change set");
            }
        }
        if (!java.util.Collections.disjoint(upsertNodeIds, deleteNodeIds)) {
            throw new IllegalArgumentException(
                    "A node cannot be upserted and deleted in the same change set");
        }
        if (!java.util.Collections.disjoint(upsertRelationIds, deleteRelationIds)) {
            throw new IllegalArgumentException(
                    "A relation cannot be upserted and deleted in the same change set");
        }
    }

    private static Set<String> copyIds(Set<String> ids, String fieldName) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        ids.forEach(id -> copy.add(GraphModelSupport.requireText(id, fieldName)));
        return Set.copyOf(copy);
    }
}

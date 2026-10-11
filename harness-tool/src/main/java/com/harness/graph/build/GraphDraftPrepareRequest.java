package com.harness.graph.build;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

@com.fasterxml.jackson.annotation.JsonIgnoreProperties({"userId", "sessionId"})
public record GraphDraftPrepareRequest(String graphId, String schemaId, String sourceDraftId,
                                       String expectedSourceContentHash, JsonNode nodes,
                                       JsonNode relations, Set<String> deleteNodeIds,
                                       Set<String> deleteRelationIds, Set<String> discardChangeIds) {
    public GraphDraftPrepareRequest {
        deleteNodeIds = deleteNodeIds == null ? Set.of() : Set.copyOf(deleteNodeIds);
        deleteRelationIds = deleteRelationIds == null ? Set.of() : Set.copyOf(deleteRelationIds);
        discardChangeIds = discardChangeIds == null ? Set.of() : Set.copyOf(discardChangeIds);
    }
}

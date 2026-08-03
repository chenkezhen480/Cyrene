package com.harness.graph.build;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.Set;

public record GraphBuildRequest(
        String requestId,
        String graphId,
        String schemaId,
        GraphBuildSourceType sourceType,
        String converterId,
        JsonNode source,
        Set<String> deleteNodeIds,
        Set<String> deleteRelationIds
) {
    public GraphBuildRequest {
        requestId = requireText(requestId, "requestId");
        graphId = requireText(graphId, "graphId");
        schemaId = requireText(schemaId, "schemaId");
        if (sourceType == null) {
            throw new IllegalArgumentException("sourceType is required");
        }
        converterId = requireText(converterId, "converterId");
        if (source == null || source.isNull()) {
            throw new IllegalArgumentException("source is required");
        }
        if (sourceType == GraphBuildSourceType.STRUCTURED && !source.isContainerNode()) {
            throw new IllegalArgumentException("structured source must be a JSON object or array");
        }
        if (sourceType == GraphBuildSourceType.NATURAL_LANGUAGE
                && (!source.isTextual() || source.asText().isBlank())) {
            throw new IllegalArgumentException("natural-language source must be non-blank text");
        }
        deleteNodeIds = copyIds(deleteNodeIds, "deleteNodeId");
        deleteRelationIds = copyIds(deleteRelationIds, "deleteRelationId");
        if (sourceType == GraphBuildSourceType.NATURAL_LANGUAGE
                && (!deleteNodeIds.isEmpty() || !deleteRelationIds.isEmpty())) {
            throw new IllegalArgumentException(
                    "Natural-language graph preview cannot contain deletion IDs");
        }
        source = source.deepCopy();
    }

    public GraphBuildRequest(
            String requestId,
            String graphId,
            String schemaId,
            GraphBuildSourceType sourceType,
            String converterId,
            JsonNode source
    ) {
        this(requestId, graphId, schemaId, sourceType, converterId, source, Set.of(), Set.of());
    }

    public boolean hasDeletions() {
        return !deleteNodeIds.isEmpty() || !deleteRelationIds.isEmpty();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }

    private static Set<String> copyIds(Set<String> ids, String fieldName) {
        if (ids == null || ids.isEmpty()) return Set.of();
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        ids.forEach(id -> copy.add(requireText(id, fieldName)));
        return Set.copyOf(copy);
    }
}

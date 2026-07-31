package com.harness.graph.build;

import com.fasterxml.jackson.databind.JsonNode;

public record GraphBuildRequest(
        String requestId,
        String graphId,
        String schemaId,
        GraphBuildSourceType sourceType,
        String converterId,
        JsonNode source
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
        source = source.deepCopy();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }
}

package com.harness.agent.graph;

/**
 * Provider-neutral reference to one logical graph space.
 */
public record GraphSpaceReference(
        String graphId,
        String schemaId,
        String description
) {
    public GraphSpaceReference {
        graphId = requireText(graphId, "graphId");
        schemaId = requireText(schemaId, "schemaId");
        description = description == null ? "" : description.trim();
    }

    public GraphSpaceReference(String graphId, String schemaId) {
        this(graphId, schemaId, "");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }
}

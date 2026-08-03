package com.harness.core.model;

import java.util.Set;

/**
 * Server-controlled graph-space scope with optional subject-node restriction.
 */
public record GraphRequestContext(
        String graphId,
        String schemaId,
        Set<String> subjectIds,
        Set<String> allowedQueryIds
) {
    public GraphRequestContext {
        graphId = requireText(graphId, "graphId");
        schemaId = requireText(schemaId, "schemaId");
        subjectIds = subjectIds == null ? Set.of() : Set.copyOf(subjectIds);
        subjectIds.forEach(subjectId -> requireText(subjectId, "subjectId"));
        allowedQueryIds = allowedQueryIds == null || allowedQueryIds.isEmpty()
                ? Set.of("anchored-neighborhood")
                : Set.copyOf(allowedQueryIds);
        allowedQueryIds.forEach(queryId -> requireText(queryId, "allowedQueryId"));
    }

    public void requireAllowedQuery(String queryId) {
        if (!allowedQueryIds.contains(queryId)) {
            throw new SecurityException("Graph query is not allowed by the request context: " + queryId);
        }
    }

    public boolean hasSubjectScope() {
        return !subjectIds.isEmpty();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }
}

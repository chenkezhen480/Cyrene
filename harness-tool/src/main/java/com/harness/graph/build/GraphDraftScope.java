package com.harness.graph.build;

import com.harness.core.model.GraphRequestContext;

/** Identity and run binding supplied by the executing server, never by tool arguments. */
public record GraphDraftScope(String tenantId, String userId, String sessionId,
                              String runId, String traceId, String taskId,
                              GraphRequestContext graphRequestContext) {
    public GraphDraftScope {
        require(tenantId, "tenantId");
        require(userId, "userId");
    }

    static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}

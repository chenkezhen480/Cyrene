package com.harness.graph.model;

public record GraphSpacePageRequest(
        int limit,
        String cursor
) {
    public GraphSpacePageRequest {
        limit = GraphModelSupport.requirePositive(limit, "limit");
        cursor = cursor == null ? "" : cursor;
    }
}

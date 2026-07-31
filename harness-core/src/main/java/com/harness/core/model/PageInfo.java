package com.harness.core.model;

public record PageInfo(
        int limit,
        String nextCursor,
        boolean hasMore
) {
    public PageInfo {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }
        nextCursor = nextCursor == null ? "" : nextCursor;
        if (hasMore && nextCursor.isBlank()) {
            throw new IllegalArgumentException("nextCursor is required when hasMore is true");
        }
    }
}

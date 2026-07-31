package com.harness.core.model;

import java.util.List;
import java.util.function.Function;

public record PageResponse<T>(
        List<T> items,
        PageInfo pageInfo
) {
    public PageResponse {
        items = items == null ? List.of() : List.copyOf(items);
        if (pageInfo == null) {
            throw new IllegalArgumentException("pageInfo is required");
        }
    }

    public static <T> PageResponse<T> fromFetched(
            List<T> fetched,
            int limit,
            Function<T, String> cursorExtractor
    ) {
        if (fetched == null) {
            throw new IllegalArgumentException("fetched is required");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }
        if (cursorExtractor == null) {
            throw new IllegalArgumentException("cursorExtractor is required");
        }
        boolean hasMore = fetched.size() > limit;
        List<T> items = hasMore
                ? List.copyOf(fetched.subList(0, limit))
                : List.copyOf(fetched);
        String nextCursor = hasMore
                ? cursorExtractor.apply(items.get(items.size() - 1))
                : "";
        return new PageResponse<>(items, new PageInfo(limit, nextCursor, hasMore));
    }
}

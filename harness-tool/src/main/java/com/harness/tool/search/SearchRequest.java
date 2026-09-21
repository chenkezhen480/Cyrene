package com.harness.tool.search;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record SearchRequest(
        String query,
        int limit,
        String language,
        Instant after,
        Instant before,
        Set<String> includeDomains,
        Set<String> excludeDomains
) {
    public SearchRequest {
        query = Objects.requireNonNull(query, "query").trim();
        if (query.isEmpty()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (limit < 1 || limit > 20) {
            throw new IllegalArgumentException("limit must be between 1 and 20");
        }
        if (after != null && before != null && after.isAfter(before)) {
            throw new IllegalArgumentException("after must not be later than before");
        }
        includeDomains = includeDomains == null ? Set.of() : Set.copyOf(includeDomains);
        excludeDomains = excludeDomains == null ? Set.of() : Set.copyOf(excludeDomains);
    }
}

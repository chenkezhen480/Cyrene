package com.harness.tool.search;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public record SearchResponse(
        List<SearchResult> results,
        String provider,
        Duration duration,
        boolean partialFailure,
        Map<String, Object> diagnostics
) {
    public SearchResponse {
        results = results == null ? List.of() : List.copyOf(results);
        duration = duration == null ? Duration.ZERO : duration;
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }
}

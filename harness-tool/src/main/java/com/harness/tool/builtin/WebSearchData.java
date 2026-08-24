package com.harness.tool.builtin;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Stable model-facing data returned by {@code web_search}. */
public record WebSearchData(String query, List<Result> results) {

    public WebSearchData {
        results = results == null ? List.of() : List.copyOf(results);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Result(
            int rank,
            String title,
            String url,
            String snippet,
            String engine,
            Double score,
            String category
    ) {
    }
}

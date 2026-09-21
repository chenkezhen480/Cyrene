package com.harness.tool.search;

import java.time.Instant;

public record SearchResult(
        String title,
        String url,
        String snippet,
        Instant publishedAt,
        String provider,
        int providerRank,
        Double providerScore,
        String category
) {
}

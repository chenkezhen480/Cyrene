package com.harness.core.model;

import java.util.Map;

public record ParsedContent(
    String text,
    ParseStrategy strategy,
    int chunkCount,
    Map<String, Object> metadata
) {
    public enum ParseStrategy { DIRECT, CHUNKED_REDUCE }
}

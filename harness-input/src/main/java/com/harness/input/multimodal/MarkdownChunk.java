package com.harness.input.multimodal;

import java.util.List;

public record MarkdownChunk(
        String content,
        int startBlockIndex,
        int endBlockIndex,
        List<String> headingPath,
        int tokenCount
) {
    public MarkdownChunk {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content cannot be blank");
        }
        if (startBlockIndex < 0 || endBlockIndex < startBlockIndex) {
            throw new IllegalArgumentException("invalid block index range");
        }
        headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
        if (tokenCount <= 0) {
            throw new IllegalArgumentException("tokenCount must be positive");
        }
    }
}

package com.harness.input.multimodal;

import java.util.Objects;

public record MarkdownBlock(
        int index,
        MarkdownBlockType type,
        String content,
        int headingLevel,
        String headingText
) {
    public MarkdownBlock {
        if (index < 0) {
            throw new IllegalArgumentException("index cannot be negative");
        }
        Objects.requireNonNull(type, "type");
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content cannot be blank");
        }
        if (type == MarkdownBlockType.HEADING) {
            if (headingLevel < 1 || headingLevel > 6) {
                throw new IllegalArgumentException("headingLevel must be between 1 and 6");
            }
            if (headingText == null || headingText.isBlank()) {
                throw new IllegalArgumentException("headingText is required for headings");
            }
        }
    }
}

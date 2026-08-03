package com.harness.input.multimodal.document;

/**
 * One stable, ordered unit of extracted document text.
 */
public record DocumentBlock(
        String blockId,
        int pageIndex,
        int order,
        String text,
        boolean visualOnly
) {
    public DocumentBlock {
        if (blockId == null || blockId.isBlank()) {
            throw new IllegalArgumentException("blockId must not be blank");
        }
        if (pageIndex < 0 || order < 0) {
            throw new IllegalArgumentException("pageIndex and order must not be negative");
        }
        text = text != null ? text : "";
    }

    public DocumentBlock withText(String repairedText) {
        return new DocumentBlock(blockId, pageIndex, order, repairedText, false);
    }
}

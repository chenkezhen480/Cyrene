package com.harness.input.multimodal.document;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Structured native extraction result retaining source bytes for local rendering.
 */
public record ExtractedDocument(
        String fileName,
        String mimeType,
        byte[] sourceData,
        List<DocumentBlock> blocks
) {
    public ExtractedDocument {
        fileName = fileName != null ? fileName : "document";
        mimeType = mimeType != null ? mimeType : "application/octet-stream";
        sourceData = sourceData != null ? Arrays.copyOf(sourceData, sourceData.length) : new byte[0];
        blocks = blocks != null
                ? blocks.stream().sorted(Comparator.comparingInt(DocumentBlock::order)).toList()
                : List.of();
    }

    @Override
    public byte[] sourceData() {
        return Arrays.copyOf(sourceData, sourceData.length);
    }

    public ExtractedDocument withBlocks(List<DocumentBlock> repairedBlocks) {
        return new ExtractedDocument(fileName, mimeType, sourceData, repairedBlocks);
    }
}

package com.harness.input.multimodal.document;

import java.util.Arrays;

public record RenderedDocumentRegion(
        int pageIndex,
        byte[] imageData,
        String mimeType
) {
    public RenderedDocumentRegion {
        if (pageIndex < 0) {
            throw new IllegalArgumentException("pageIndex must not be negative");
        }
        imageData = imageData != null ? Arrays.copyOf(imageData, imageData.length) : new byte[0];
        mimeType = mimeType != null ? mimeType : "image/png";
    }

    @Override
    public byte[] imageData() {
        return Arrays.copyOf(imageData, imageData.length);
    }
}

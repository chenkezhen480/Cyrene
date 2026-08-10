package com.harness.input.document;

import java.util.Objects;

/**
 * Immutable document payload passed to a {@link DocumentConversionService}.
 */
public record DocumentConversionRequest(
        byte[] fileData,
        String fileName,
        String mimeType
) {

    private static final String DEFAULT_MIME_TYPE = "application/octet-stream";

    public DocumentConversionRequest {
        Objects.requireNonNull(fileData, "fileData");
        if (fileData.length == 0) {
            throw new IllegalArgumentException("fileData must not be empty");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
        fileData = fileData.clone();
        fileName = fileName.trim();
        mimeType = mimeType == null || mimeType.isBlank()
                ? DEFAULT_MIME_TYPE
                : mimeType.trim();
    }

    @Override
    public byte[] fileData() {
        return fileData.clone();
    }
}

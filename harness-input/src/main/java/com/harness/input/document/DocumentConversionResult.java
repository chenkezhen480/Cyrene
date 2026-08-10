package com.harness.input.document;

import java.util.Objects;

/**
 * Canonical Markdown and parser metadata produced for one document.
 */
public record DocumentConversionResult(
        String markdown,
        String title,
        String detectedMimeType,
        DocumentConversionDiagnostics diagnostics
) {

    public DocumentConversionResult {
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException("markdown must not be blank");
        }
        if (detectedMimeType == null || detectedMimeType.isBlank()) {
            throw new IllegalArgumentException("detectedMimeType must not be blank");
        }
        Objects.requireNonNull(diagnostics, "diagnostics");
        title = title == null || title.isBlank() ? null : title.trim();
        detectedMimeType = detectedMimeType.trim();
    }
}

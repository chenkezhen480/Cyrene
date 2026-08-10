package com.harness.input.document;

import java.util.List;
import java.util.Objects;

/**
 * Structured diagnostics returned by the document parser worker.
 */
public record DocumentConversionDiagnostics(
        String converter,
        String model,
        boolean ocrEnabled,
        List<String> warnings,
        String visionSource,
        int visionCalls,
        long elapsedMs,
        long inputBytes
) {

    public DocumentConversionDiagnostics {
        if (converter == null || converter.isBlank()) {
            throw new IllegalArgumentException("diagnostics.converter must not be blank");
        }
        Objects.requireNonNull(warnings, "diagnostics.warnings");
        if (visionSource == null || visionSource.isBlank()) {
            throw new IllegalArgumentException("diagnostics.visionSource must not be blank");
        }
        if (visionCalls < 0) {
            throw new IllegalArgumentException("diagnostics.visionCalls must not be negative");
        }
        if (elapsedMs < 0) {
            throw new IllegalArgumentException("diagnostics.elapsedMs must not be negative");
        }
        if (inputBytes < 0) {
            throw new IllegalArgumentException("diagnostics.inputBytes must not be negative");
        }
        converter = converter.trim();
        model = model == null || model.isBlank() ? null : model.trim();
        warnings = List.copyOf(warnings);
        visionSource = visionSource.trim();
    }
}

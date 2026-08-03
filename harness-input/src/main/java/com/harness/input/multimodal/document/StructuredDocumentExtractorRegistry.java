package com.harness.input.multimodal.document;

import java.util.List;

public final class StructuredDocumentExtractorRegistry {

    private final List<StructuredDocumentExtractor> extractors;

    public StructuredDocumentExtractorRegistry(List<StructuredDocumentExtractor> extractors) {
        this.extractors = List.copyOf(extractors);
    }

    public static StructuredDocumentExtractorRegistry withDefaults() {
        return new StructuredDocumentExtractorRegistry(List.of(
                new PdfStructuredDocumentExtractor(),
                new PptxStructuredDocumentExtractor()));
    }

    public boolean supports(String mimeType) {
        return extractors.stream().anyMatch(extractor -> extractor.supports(mimeType));
    }

    public ExtractedDocument extract(byte[] data, String fileName, String mimeType) {
        StructuredDocumentExtractor extractor = extractors.stream()
                .filter(candidate -> candidate.supports(mimeType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No structured extractor for MIME type: " + mimeType));
        try {
            return extractor.extract(data, fileName, mimeType);
        } catch (Exception e) {
            throw new IllegalStateException("Structured document extraction failed", e);
        }
    }
}

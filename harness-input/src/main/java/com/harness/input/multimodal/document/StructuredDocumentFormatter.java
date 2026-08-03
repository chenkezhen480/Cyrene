package com.harness.input.multimodal.document;

import java.util.stream.Collectors;

public final class StructuredDocumentFormatter {

    private StructuredDocumentFormatter() {
    }

    public static String format(ExtractedDocument document) {
        return document.blocks().stream()
                .map(DocumentBlock::text)
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n\n"));
    }
}

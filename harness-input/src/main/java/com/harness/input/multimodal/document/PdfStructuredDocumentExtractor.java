package com.harness.input.multimodal.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

import java.util.ArrayList;
import java.util.List;

public final class PdfStructuredDocumentExtractor implements StructuredDocumentExtractor {

    public static final String MIME_TYPE = "application/pdf";

    @Override
    public boolean supports(String mimeType) {
        return MIME_TYPE.equalsIgnoreCase(mimeType);
    }

    @Override
    public ExtractedDocument extract(byte[] data, String fileName, String mimeType) throws Exception {
        List<DocumentBlock> blocks = new ArrayList<>();
        try (var document = Loader.loadPDF(data)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                stripper.setStartPage(pageIndex + 1);
                stripper.setEndPage(pageIndex + 1);
                String text = stripper.getText(document).trim();
                blocks.add(new DocumentBlock(
                        "pdf-page-" + (pageIndex + 1),
                        pageIndex,
                        pageIndex,
                        text,
                        text.isBlank()));
            }
        }
        return new ExtractedDocument(fileName, MIME_TYPE, data, blocks);
    }
}

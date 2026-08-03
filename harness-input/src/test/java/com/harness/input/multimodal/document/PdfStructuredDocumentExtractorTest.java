package com.harness.input.multimodal.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class PdfStructuredDocumentExtractorTest {

    @Test
    void retainsBlankScannedPageAsVisualBlock() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            pdf = output.toByteArray();
        }

        ExtractedDocument extracted = new PdfStructuredDocumentExtractor()
                .extract(pdf, "scan.pdf", "application/pdf");

        assertThat(extracted.blocks()).singleElement().satisfies(block -> {
            assertThat(block.blockId()).isEqualTo("pdf-page-1");
            assertThat(block.visualOnly()).isTrue();
        });
    }
}

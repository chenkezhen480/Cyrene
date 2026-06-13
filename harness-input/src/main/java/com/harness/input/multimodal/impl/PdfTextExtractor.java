package com.harness.input.multimodal.impl;

import com.harness.input.multimodal.TextExtractor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

public class PdfTextExtractor implements TextExtractor {

    @Override
    public boolean supports(String mimeType) {
        return mimeType != null && mimeType.toLowerCase().equals("application/pdf");
    }

    @Override
    public String extract(byte[] data, String mimeType) throws Exception {
        try (var document = Loader.loadPDF(data)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }
}

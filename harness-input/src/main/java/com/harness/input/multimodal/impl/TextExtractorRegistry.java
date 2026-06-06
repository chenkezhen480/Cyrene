package com.harness.input.multimodal.impl;

import com.harness.input.multimodal.TextExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class TextExtractorRegistry {

    private static final Logger log = LoggerFactory.getLogger(TextExtractorRegistry.class);
    private static final List<TextExtractor> EXTRACTORS = new ArrayList<>();

    static {
        EXTRACTORS.add(new PlainTextExtractor());
        EXTRACTORS.add(new PdfTextExtractor());
        EXTRACTORS.add(new OfficeTextExtractor());
    }

    public static String extract(byte[] data, String fileName, String mimeType) {
        // Guess mimeType from extension if not detected
        String effectiveMime = mimeType;
        if (mimeType == null || mimeType.equals("application/octet-stream")) {
            effectiveMime = guessMimeType(fileName);
            if (effectiveMime != null) {
                log.info("Guessed mimeType={} from fileName={}", effectiveMime, fileName);
            }
        }

        for (TextExtractor extractor : EXTRACTORS) {
            if (extractor.supports(effectiveMime)) {
                try {
                    log.info("Extracting text using {} (mimeType={}, file={})", extractor.getClass().getSimpleName(), effectiveMime, fileName);
                    return extractor.extract(data, effectiveMime);
                } catch (Exception e) {
                    log.warn("Extraction failed with {}: {}", extractor.getClass().getSimpleName(), e.getMessage());
                }
            }
        }
        log.info("No extractor matched for mimeType={}, falling back to UTF-8", effectiveMime);
        return new String(data, StandardCharsets.UTF_8);
    }

    public static String guessMimeType(String fileName) {
        if (fileName == null) return null;
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".xls")) return "application/vnd.ms-excel";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".xml")) return "text/xml";
        return null;
    }
}

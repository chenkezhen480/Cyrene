package com.harness.input.multimodal.impl;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TextExtractorRegistryTest {

    // ---- guessMimeType ----

    @Test
    void guessMimeType_pdf() {
        assertThat(TextExtractorRegistry.guessMimeType("report.pdf")).isEqualTo("application/pdf");
    }

    @Test
    void guessMimeType_docx() {
        assertThat(TextExtractorRegistry.guessMimeType("doc.docx")).isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    @Test
    void guessMimeType_doc() {
        assertThat(TextExtractorRegistry.guessMimeType("old.doc")).isEqualTo("application/msword");
    }

    @Test
    void guessMimeType_xlsx() {
        assertThat(TextExtractorRegistry.guessMimeType("sheet.xlsx")).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    @Test
    void guessMimeType_xls() {
        assertThat(TextExtractorRegistry.guessMimeType("old.xls")).isEqualTo("application/vnd.ms-excel");
    }

    @Test
    void guessMimeType_txt() {
        assertThat(TextExtractorRegistry.guessMimeType("readme.txt")).isEqualTo("text/plain");
    }

    @Test
    void guessMimeType_md() {
        assertThat(TextExtractorRegistry.guessMimeType("README.md")).isEqualTo("text/markdown");
    }

    @Test
    void guessMimeType_csv() {
        assertThat(TextExtractorRegistry.guessMimeType("data.csv")).isEqualTo("text/csv");
    }

    @Test
    void guessMimeType_json() {
        assertThat(TextExtractorRegistry.guessMimeType("config.json")).isEqualTo("application/json");
    }

    @Test
    void guessMimeType_xml() {
        assertThat(TextExtractorRegistry.guessMimeType("pom.xml")).isEqualTo("text/xml");
    }

    @Test
    void guessMimeType_unknown_returnsNull() {
        assertThat(TextExtractorRegistry.guessMimeType("file.xyz")).isNull();
    }

    @Test
    void guessMimeType_null_returnsNull() {
        assertThat(TextExtractorRegistry.guessMimeType(null)).isNull();
    }

    // ---- extract ----

    @Test
    void extract_txtFile_usesPlainTextExtractor() {
        byte[] data = "Hello".getBytes(StandardCharsets.UTF_8);
        String result = TextExtractorRegistry.extract(data, "test.txt", "text/plain");
        assertThat(result).isEqualTo("Hello");
    }

    @Test
    void extract_nullMimeType_guessesFromFilename() {
        byte[] data = "content".getBytes(StandardCharsets.UTF_8);
        String result = TextExtractorRegistry.extract(data, "test.txt", null);
        assertThat(result).isEqualTo("content");
    }

    @Test
    void extract_octetStreamMimeType_guessesFromFilename() {
        byte[] data = "content".getBytes(StandardCharsets.UTF_8);
        String result = TextExtractorRegistry.extract(data, "test.json", "application/octet-stream");
        assertThat(result).isEqualTo("content");
    }

    @Test
    void extract_unknownType_fallsBackToUtf8() {
        byte[] data = "fallback content".getBytes(StandardCharsets.UTF_8);
        String result = TextExtractorRegistry.extract(data, "test.xyz", "application/unknown");
        assertThat(result).isEqualTo("fallback content");
    }
}

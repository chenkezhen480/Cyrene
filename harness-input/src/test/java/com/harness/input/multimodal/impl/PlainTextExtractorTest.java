package com.harness.input.multimodal.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PlainTextExtractorTest {

    PlainTextExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new PlainTextExtractor();
    }

    @Test
    void supports_textPlain_returnsTrue() {
        assertThat(extractor.supports("text/plain")).isTrue();
    }

    @Test
    void supports_textHtml_returnsTrue() {
        assertThat(extractor.supports("text/html")).isTrue();
    }

    @Test
    void supports_applicationJson_returnsTrue() {
        assertThat(extractor.supports("application/json")).isTrue();
    }

    @Test
    void supports_applicationXml_returnsTrue() {
        assertThat(extractor.supports("application/xml")).isTrue();
    }

    @Test
    void supports_textCsv_returnsTrue() {
        assertThat(extractor.supports("text/csv")).isTrue();
    }

    @Test
    void supports_applicationPdf_returnsFalse() {
        assertThat(extractor.supports("application/pdf")).isFalse();
    }

    @Test
    void supports_imagePng_returnsFalse() {
        assertThat(extractor.supports("image/png")).isFalse();
    }

    @Test
    void extract_utf8Content_returnsText() {
        byte[] data = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        String result = extractor.extract(data, "text/plain");
        assertThat(result).isEqualTo("Hello, World!");
    }

    @Test
    void extract_chineseContent_returnsText() {
        byte[] data = "你好世界".getBytes(StandardCharsets.UTF_8);
        String result = extractor.extract(data, "text/plain");
        assertThat(result).isEqualTo("你好世界");
    }

    @Test
    void extract_jsonContent_returnsText() {
        byte[] data = "{\"key\": \"value\"}".getBytes(StandardCharsets.UTF_8);
        String result = extractor.extract(data, "application/json");
        assertThat(result).isEqualTo("{\"key\": \"value\"}");
    }
}

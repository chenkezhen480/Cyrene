package com.harness.core.knowledge;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class KnowledgeCursorSupport {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private KnowledgeCursorSupport() {
    }

    static String encode(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static String decode(String value) {
        return new String(DECODER.decode(value), StandardCharsets.UTF_8);
    }

    static String[] split(String value, int expectedParts, String cursorName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(cursorName + " cursor is required");
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != expectedParts) {
            throw new IllegalArgumentException("Invalid " + cursorName + " cursor");
        }
        return parts;
    }
}

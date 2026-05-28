package com.harness.input.multimodal.impl;

import com.harness.input.multimodal.TextExtractor;

import java.nio.charset.StandardCharsets;

public class PlainTextExtractor implements TextExtractor {

    @Override
    public boolean supports(String mimeType) {
        if (mimeType == null) return false;
        String m = mimeType.toLowerCase();
        return m.startsWith("text/")
                || m.contains("json")
                || m.contains("xml")
                || m.contains("csv");
    }

    @Override
    public String extract(byte[] data, String mimeType) {
        return new String(data, StandardCharsets.UTF_8);
    }
}

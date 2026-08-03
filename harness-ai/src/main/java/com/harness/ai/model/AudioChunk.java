package com.harness.ai.model;

import java.util.Arrays;

/**
 * An ordered byte chunk produced by a streaming TTS request.
 */
public record AudioChunk(
        long sequence,
        byte[] data,
        String mimeType
) {
    public AudioChunk {
        data = data != null ? Arrays.copyOf(data, data.length) : new byte[0];
        mimeType = mimeType != null ? mimeType : "application/octet-stream";
    }

    @Override
    public byte[] data() {
        return Arrays.copyOf(data, data.length);
    }
}

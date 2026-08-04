package com.harness.provider;

/**
 * One ordered phrase submitted to the configured TTS API.
 */
public record SynthesisRequest(
        long sequence,
        String text,
        String voice,
        double speed,
        String responseFormat,
        String streamFormat
) {
    public SynthesisRequest {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        if (speed <= 0) {
            throw new IllegalArgumentException("speed must be positive");
        }
    }
}

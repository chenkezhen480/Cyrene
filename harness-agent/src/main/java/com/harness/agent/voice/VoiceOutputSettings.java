package com.harness.agent.voice;

import com.harness.provider.VoiceModelProvider;

/**
 * Validated per-process settings for phrase-level streamed voice replies.
 */
public record VoiceOutputSettings(
        String streamFormat,
        String responseFormat,
        String defaultVoice,
        double speed,
        int minChars,
        int softChars,
        int maxChars,
        int queueCapacity,
        int maxTotalChars
) {

    private static final String DEFAULT_STREAM_FORMAT = "audio";
    private static final String DEFAULT_RESPONSE_FORMAT = "mp3";
    private static final String DEFAULT_VOICE = "alloy";
    private static final double DEFAULT_SPEED = 1.0;
    private static final int DEFAULT_MIN_CHARS = 12;
    private static final int DEFAULT_SOFT_CHARS = 30;
    private static final int DEFAULT_MAX_CHARS = 60;
    private static final int DEFAULT_QUEUE_CAPACITY = 4;
    private static final int DEFAULT_MAX_TOTAL_CHARS = 12_000;

    public VoiceOutputSettings {
        if (streamFormat == null || streamFormat.isBlank()) {
            throw new IllegalArgumentException("streamFormat must not be blank");
        }
        if (!"audio".equalsIgnoreCase(streamFormat) && !"sse".equalsIgnoreCase(streamFormat)) {
            throw new IllegalArgumentException("streamFormat must be audio or sse");
        }
        if (responseFormat == null || responseFormat.isBlank()) {
            throw new IllegalArgumentException("responseFormat must not be blank");
        }
        if (defaultVoice == null || defaultVoice.isBlank()) {
            throw new IllegalArgumentException("defaultVoice must not be blank");
        }
        if (speed <= 0 || speed > 4) {
            throw new IllegalArgumentException("speed must be in range (0, 4]");
        }
        if (minChars <= 0 || minChars > softChars || softChars > maxChars) {
            throw new IllegalArgumentException("TTS chunk sizes must satisfy 0 < min <= soft <= max");
        }
        if (queueCapacity <= 0 || maxTotalChars <= 0) {
            throw new IllegalArgumentException("queueCapacity and maxTotalChars must be positive");
        }
    }

    public static VoiceOutputSettings fromProvider(VoiceModelProvider provider) {
        return new VoiceOutputSettings(
                DEFAULT_STREAM_FORMAT,
                DEFAULT_RESPONSE_FORMAT,
                provider != null ? provider.defaultVoice() : DEFAULT_VOICE,
                DEFAULT_SPEED,
                DEFAULT_MIN_CHARS,
                DEFAULT_SOFT_CHARS,
                DEFAULT_MAX_CHARS,
                DEFAULT_QUEUE_CAPACITY,
                DEFAULT_MAX_TOTAL_CHARS);
    }
}

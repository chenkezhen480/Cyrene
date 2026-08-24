package com.harness.provider.impl;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** Supported upstream protocols for OpenAI-compatible chat providers. */
public enum OpenAiChatApiFormat {
    CHAT_COMPLETIONS("chat_completions"),
    RESPONSES("responses");

    private final String configValue;

    OpenAiChatApiFormat(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    public static OpenAiChatApiFormat parse(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(format -> format.configValue.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Invalid HARNESS_MODEL_CHAT_API_FORMAT '" + value
                                + "'. Allowed values: " + allowedValues()));
    }

    private static String allowedValues() {
        return Arrays.stream(values())
                .map(OpenAiChatApiFormat::configValue)
                .collect(Collectors.joining(", "));
    }
}

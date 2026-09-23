package com.harness.provider.impl;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** Wire protocol used by the configured chat endpoint. */
public enum ChatApiFormat {
    CHAT_COMPLETIONS("chat_completions"),
    RESPONSES("responses"),
    MESSAGES("messages");

    private final String configValue;

    ChatApiFormat(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    public static ChatApiFormat parse(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(format -> format.configValue.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Invalid chat.apiFormat '" + value
                                + "'. Allowed values: " + allowedValues()));
    }

    private static String allowedValues() {
        return Arrays.stream(values())
                .map(ChatApiFormat::configValue)
                .collect(Collectors.joining(", "));
    }
}

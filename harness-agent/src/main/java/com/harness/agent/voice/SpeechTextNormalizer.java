package com.harness.agent.voice;

import java.util.regex.Pattern;

/**
 * Deterministic Markdown-to-speech cleanup. It never invokes another model.
 */
final class SpeechTextNormalizer {

    private static final Pattern FENCED_CODE = Pattern.compile("(?s)```.*?```");
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^]]+)]\\([^)]*\\)");
    private static final Pattern MARKDOWN_MARKERS = Pattern.compile("(?m)^[ \\t]*(?:#{1,6}|[-*+]|>)[ \\t]+|[*_~`]");
    private static final Pattern WHITESPACE = Pattern.compile("[ \\t\\x0B\\f\\r]+");

    private SpeechTextNormalizer() {
    }

    static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = FENCED_CODE.matcher(text).replaceAll(" ");
        normalized = MARKDOWN_LINK.matcher(normalized).replaceAll("$1");
        normalized = URL.matcher(normalized).replaceAll(" ");
        normalized = MARKDOWN_MARKERS.matcher(normalized).replaceAll("");
        normalized = WHITESPACE.matcher(normalized).replaceAll(" ");
        return normalized.trim();
    }
}

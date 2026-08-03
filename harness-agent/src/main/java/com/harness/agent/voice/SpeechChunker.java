package com.harness.agent.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Stateful deterministic token accumulator that emits stable TTS phrases.
 */
public final class SpeechChunker {

    private static final Set<Character> STRONG_BOUNDARIES = Set.of('。', '！', '？', '；', '!', '?', ';', '\n');
    private static final Set<Character> SOFT_BOUNDARIES = Set.of('，', '：', '、', ',', ':', ' ');

    private final int minChars;
    private final int softChars;
    private final int maxChars;
    private final StringBuilder buffer = new StringBuilder();

    public SpeechChunker(int minChars, int softChars, int maxChars) {
        if (minChars <= 0 || minChars > softChars || softChars > maxChars) {
            throw new IllegalArgumentException("chunk sizes must satisfy 0 < min <= soft <= max");
        }
        this.minChars = minChars;
        this.softChars = softChars;
        this.maxChars = maxChars;
    }

    public synchronized List<String> append(String token) {
        if (token != null && !token.isEmpty()) {
            buffer.append(token);
        }
        return drain(false);
    }

    public synchronized List<String> finish() {
        return drain(true);
    }

    private List<String> drain(boolean finishing) {
        List<String> chunks = new ArrayList<>();
        while (buffer.length() > 0) {
            int boundary = findStrongBoundary();
            if (boundary < 0 && buffer.length() >= softChars) {
                boundary = findSoftBoundary();
            }
            if (boundary < 0 && buffer.length() >= maxChars) {
                boundary = findHardBoundary();
            }
            if (boundary < 0) {
                if (finishing) {
                    addNormalized(chunks, buffer.toString());
                    buffer.setLength(0);
                }
                break;
            }

            String rawChunk = buffer.substring(0, boundary);
            buffer.delete(0, boundary);
            addNormalized(chunks, rawChunk);
        }
        return chunks;
    }

    private int findStrongBoundary() {
        for (int i = minChars - 1; i < buffer.length(); i++) {
            if (STRONG_BOUNDARIES.contains(buffer.charAt(i))) {
                int candidate = safeBoundary(i + 1);
                if (!isUnsafeBoundary(candidate)) {
                    return candidate;
                }
            }
            if (i + 1 >= maxChars) {
                return -1;
            }
        }
        return -1;
    }

    private int findSoftBoundary() {
        int upper = Math.min(buffer.length(), maxChars);
        for (int i = upper - 1; i >= softChars - 1; i--) {
            if (SOFT_BOUNDARIES.contains(buffer.charAt(i))) {
                int candidate = safeBoundary(i + 1);
                if (!isUnsafeBoundary(candidate)) {
                    return candidate;
                }
            }
        }
        return -1;
    }

    private int findHardBoundary() {
        int candidate = safeBoundary(maxChars);
        int urlStart = lastUrlStart(candidate);
        int codeStart = lastUnclosedCodeFenceStart(candidate);
        int protectedStart = firstNonNegative(urlStart, codeStart);
        if (protectedStart < 0) {
            return candidate;
        }

        if (protectedStart == urlStart) {
            int urlEnd = firstWhitespaceAfter(urlStart);
            if (urlEnd >= 0) {
                return safeBoundary(urlEnd + 1);
            }
        } else {
            int codeEnd = buffer.indexOf("```", codeStart + 3);
            if (codeEnd >= 0) {
                return safeBoundary(codeEnd + 3);
            }
        }
        return protectedStart >= minChars ? safeBoundary(protectedStart) : -1;
    }

    private boolean isUnsafeBoundary(int boundary) {
        return isInsideUrl(boundary) || lastUnclosedCodeFenceStart(boundary) >= 0;
    }

    private boolean isInsideUrl(int boundary) {
        int urlStart = lastUrlStart(boundary);
        if (urlStart < 0) {
            return false;
        }
        int urlEnd = firstWhitespaceAfter(urlStart);
        return urlEnd < 0 || urlEnd >= boundary;
    }

    private int lastUrlStart(int before) {
        int searchEnd = Math.min(before, buffer.length());
        String prefix = buffer.substring(0, searchEnd).toLowerCase(java.util.Locale.ROOT);
        int httpsStart = Math.max(prefix.lastIndexOf("https://"), prefix.lastIndexOf("https:"));
        int httpStart = Math.max(prefix.lastIndexOf("http://"), prefix.lastIndexOf("http:"));
        return Math.max(httpsStart, httpStart);
    }

    private int lastUnclosedCodeFenceStart(int before) {
        int searchEnd = Math.min(before, buffer.length());
        int fenceStart = -1;
        int searchFrom = 0;
        while (searchFrom < searchEnd) {
            int fence = buffer.indexOf("```", searchFrom);
            if (fence < 0 || fence >= searchEnd) {
                break;
            }
            fenceStart = fenceStart < 0 ? fence : -1;
            searchFrom = fence + 3;
        }
        return fenceStart;
    }

    private static int firstNonNegative(int first, int second) {
        if (first < 0) {
            return second;
        }
        if (second < 0) {
            return first;
        }
        return Math.min(first, second);
    }

    private int firstWhitespaceAfter(int start) {
        for (int i = start; i < buffer.length(); i++) {
            if (Character.isWhitespace(buffer.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private int safeBoundary(int requested) {
        int boundary = Math.min(requested, buffer.length());
        if (boundary > 0 && boundary < buffer.length()
                && Character.isHighSurrogate(buffer.charAt(boundary - 1))
                && Character.isLowSurrogate(buffer.charAt(boundary))) {
            boundary--;
        }
        return Math.max(boundary, 1);
    }

    private static void addNormalized(List<String> chunks, String rawChunk) {
        String normalized = SpeechTextNormalizer.normalize(rawChunk);
        if (!normalized.isBlank()) {
            chunks.add(normalized);
        }
    }
}

package com.harness.core.text;

/**
 * Deterministic fallback estimator calibrated for mixed Latin, CJK, code, and emoji text.
 * It is intentionally conservative because it is used to enforce chunk budgets.
 */
public final class UnicodeAwareTextTokenEstimator implements TextTokenEstimator {

    public static final UnicodeAwareTextTokenEstimator INSTANCE =
            new UnicodeAwareTextTokenEstimator();

    private UnicodeAwareTextTokenEstimator() {
    }

    @Override
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int tokens = 0;
        int latinRunLength = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if (isLatinWordCodePoint(codePoint)) {
                latinRunLength++;
                continue;
            }
            tokens += latinTokens(latinRunLength);
            latinRunLength = 0;

            if (Character.isWhitespace(codePoint)) {
                continue;
            }
            if (isCjk(codePoint)) {
                tokens++;
            } else if (isEmoji(codePoint)) {
                tokens += 2;
            } else if (Character.isLetterOrDigit(codePoint)) {
                tokens++;
            } else {
                tokens++;
            }
        }
        return tokens + latinTokens(latinRunLength);
    }

    @Override
    public String strategyName() {
        return "unicode-aware-estimate-v1";
    }

    private static int latinTokens(int runLength) {
        return runLength == 0 ? 0 : Math.max(1, (runLength + 3) / 4);
    }

    private static boolean isLatinWordCodePoint(int codePoint) {
        return codePoint < 128 && (Character.isLetterOrDigit(codePoint) || codePoint == '_');
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private static boolean isEmoji(int codePoint) {
        return (codePoint >= 0x1F000 && codePoint <= 0x1FAFF)
                || (codePoint >= 0x2600 && codePoint <= 0x27BF);
    }
}

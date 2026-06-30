package com.harness.input.multimodal;

import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Static text chunking utility. Splits text by semantic boundaries
 * (paragraphs → sentences → lines → fixed token count).
 * Each semantic unit becomes its own chunk — no merging across boundaries.
 */
public final class TextChunker {

    private TextChunker() {}

    // Paragraph separator: two or more newlines
    private static final Pattern PARAGRAPH_SEP = Pattern.compile("\\n\\n+");

    // Heading patterns: markdown headings or lines that look like titles
    private static final Pattern HEADING_PATTERN = Pattern.compile("^#{1,6}\\s.*$", Pattern.MULTILINE);

    // Horizontal rule: ---, ===, ***, - - -, etc.
    private static final Pattern HR_PATTERN = Pattern.compile("^[-=*]{3,}\\s*$", Pattern.MULTILINE);

    // Sentence-ending punctuation (CJK + Latin)
    private static final String SENTENCE_ENDINGS = "。.！!？?；;";

    public static List<String> split(String text) {
        return split(text, defaultChunkTokenSize());
    }

    public static List<String> split(String text, int chunkTokenSize) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;

        // Step 1: Split by semantic boundaries (paragraphs, headings, horizontal rules)
        List<String> semanticUnits = splitBySemanticBoundaries(text);

        // Step 2: Each semantic unit becomes its own chunk; oversized ones get sub-split
        for (String unit : semanticUnits) {
            if (unit.isBlank()) continue;
            int tokens = estimateTokens(unit);
            if (tokens <= chunkTokenSize) {
                chunks.add(unit.strip());
            } else {
                // Sub-split by sentence boundaries
                chunks.addAll(splitBySentence(unit, chunkTokenSize));
            }
        }

        return chunks;
    }

    /**
     * Split text by semantic boundaries: paragraphs, headings, horizontal rules.
     * Does NOT merge units — each unit is returned as-is.
     */
    private static List<String> splitBySemanticBoundaries(String text) {
        List<String> units = new ArrayList<>();

        // Split on: horizontal rules, markdown headings, paragraph breaks
        // Use a combined pattern: HR or double-newline
        String[] rawParts = text.split("(?m)(?=^#{1,6}\\s)|\\n\\n+|(?=^[-=*]{3,}\\s*$)");

        for (String part : rawParts) {
            if (!part.isBlank()) {
                units.add(part.strip());
            }
        }

        return units;
    }

    /**
     * Split a large semantic unit by sentence boundaries.
     * Uses sentence-ending punctuation as split points.
     */
    private static List<String> splitBySentence(String text, int chunkTokenSize) {
        List<String> chunks = new ArrayList<>();

        // Split on sentence-ending punctuation, keeping the delimiter with the preceding sentence
        List<String> sentences = splitSentences(text);

        StringBuilder current = new StringBuilder();
        int currentTokens = 0;

        for (String sentence : sentences) {
            int sentenceTokens = estimateTokens(sentence);
            if (currentTokens + sentenceTokens > chunkTokenSize && !current.isEmpty()) {
                chunks.add(current.toString().strip());
                current = new StringBuilder();
                currentTokens = 0;
            }
            current.append(sentence);
            currentTokens += sentenceTokens;
        }
        if (!current.toString().isBlank()) {
            String remaining = current.toString().strip();
            if (estimateTokens(remaining) > chunkTokenSize * 1.5) {
                // Still oversized — fall back to line splitting
                chunks.addAll(splitByLine(remaining, chunkTokenSize));
            } else {
                chunks.add(remaining);
            }
        }

        return chunks;
    }

    /**
     * Split text into sentences by sentence-ending punctuation.
     * Keeps the punctuation attached to the preceding text.
     */
    private static List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);

            if (SENTENCE_ENDINGS.indexOf(c) >= 0) {
                // Look ahead: if next char is a quote/bracket, include it
                int next = i + 1;
                while (next < text.length()) {
                    char nc = text.charAt(next);
                    if (nc == '"' || nc == '\'' || nc == '"' || nc == '"' || nc == '\''
                            || nc == '」' || nc == '』' || nc == ')' || nc == ']') {
                        current.append(nc);
                        next++;
                        i++;
                    } else {
                        break;
                    }
                }
                sentences.add(current.toString());
                current = new StringBuilder();
            }
        }

        if (!current.toString().isBlank()) {
            sentences.add(current.toString());
        }

        return sentences;
    }

    /**
     * Split by single newlines. Last resort before fixed-token splitting.
     */
    private static List<String> splitByLine(String text, int chunkTokenSize) {
        List<String> chunks = new ArrayList<>();
        String[] lines = text.split("\\n");
        StringBuilder current = new StringBuilder();
        int currentTokens = 0;

        for (String line : lines) {
            int lineTokens = estimateTokens(line);
            if (currentTokens + lineTokens > chunkTokenSize && !current.isEmpty()) {
                chunks.add(current.toString().strip());
                current = new StringBuilder();
                currentTokens = 0;
            }
            current.append(line).append("\n");
            currentTokens += lineTokens;
        }
        if (!current.toString().isBlank()) {
            chunks.add(current.toString().strip());
        }
        return chunks;
    }

    static int estimateTokens(String text) {
        if (text == null) return 0;
        return text.length() / 3;
    }

    private static int defaultChunkTokenSize() {
        return EnvConfig.get().getInt(EnvKey.INPUT_CHUNK_TOKEN_SIZE, 1024);
    }

    /**
     * 单遍贪心合并：相邻 chunk 若合计 token < chunkTokenSize 则合并。
     * 硬边界保护：遇到 Markdown 标题或分割线开头的 chunk 则强制断开。
     *
     * @param chunks 原始 chunk 列表
     * @param chunkTokenSize 目标 token 数
     * @return 合并后的 chunk 列表
     */
    public static List<String> mergeSmallChunks(List<String> chunks, int chunkTokenSize) {
        if (chunks == null || chunks.size() <= 1) return chunks;
        List<String> merged = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentTokens = 0;

        for (String chunk : chunks) {
            int chunkTokens = estimateTokens(chunk);
            boolean isHardBoundary = startsWithHeading(chunk) || startsWithDivider(chunk);

            if (currentTokens > 0 && currentTokens + chunkTokens <= chunkTokenSize && !isHardBoundary) {
                current.append("\n\n").append(chunk);
                currentTokens += chunkTokens;
            } else {
                if (currentTokens > 0) merged.add(current.toString().strip());
                current = new StringBuilder(chunk);
                currentTokens = chunkTokens;
            }
        }
        if (currentTokens > 0) merged.add(current.toString().strip());
        return merged;
    }

    private static boolean startsWithHeading(String chunk) {
        if (chunk == null || chunk.isEmpty()) return false;
        String firstLine = chunk.stripLeading();
        int nl = firstLine.indexOf('\n');
        if (nl > 0) firstLine = firstLine.substring(0, nl);
        // Markdown 标题：# ## ### 等
        if (firstLine.matches("^#{1,6}\\s+.*")) return true;
        // 数字编号标题：1. / 1、 / (1) / 第X章 / 第X节
        if (firstLine.matches("^(第.+[章节]|\\d+[.、)）]\\s*).*")) return true;
        return false;
    }

    private static boolean startsWithDivider(String chunk) {
        if (chunk == null || chunk.isEmpty()) return false;
        String trimmed = chunk.stripLeading();
        return trimmed.startsWith("---") || trimmed.startsWith("***") || trimmed.startsWith("___");
    }
}

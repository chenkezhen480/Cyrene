package com.harness.input.multimodal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextChunkerTest {

    // ---- split(text, chunkTokenSize) ----

    @Test
    void split_nullText_returnsEmptyList() {
        assertThat(TextChunker.split(null, 100)).isEmpty();
    }

    @Test
    void split_blankText_returnsEmptyList() {
        assertThat(TextChunker.split("   ", 100)).isEmpty();
    }

    @Test
    void split_shortText_returnsSingleChunk() {
        String text = "This is a short text.";
        List<String> chunks = TextChunker.split(text, 100);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo(text);
    }

    @Test
    void split_paragraphBreak_splitsIntoTwoChunks() {
        String text = "First paragraph.\n\nSecond paragraph.";
        List<String> chunks = TextChunker.split(text, 100);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).isEqualTo("First paragraph.");
        assertThat(chunks.get(1)).isEqualTo("Second paragraph.");
    }

    @Test
    void split_markdownHeading_splitsAtHeading() {
        String text = "Some content.\n## New Section\nMore content.";
        List<String> chunks = TextChunker.split(text, 100);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).isEqualTo("Some content.");
        assertThat(chunks.get(1)).contains("## New Section");
    }

    @Test
    void split_horizontalRule_splitsAtRule() {
        String text = "Before the rule.\n\n---\n\nAfter the rule.";
        List<String> chunks = TextChunker.split(text, 100);

        assertThat(chunks).hasSize(3);
    }

    @Test
    void split_largeText_splitsIntoMultipleChunks() {
        // Each sentence ~20 tokens (60 chars), chunk size 50 tokens => ~2-3 sentences per chunk
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("This is sentence number ").append(i).append(" with some extra text. ");
        }
        String text = sb.toString().strip();
        List<String> chunks = TextChunker.split(text, 50);

        assertThat(chunks.size()).isGreaterThan(1);
        // Each chunk should be non-blank
        for (String chunk : chunks) {
            assertThat(chunk).isNotBlank();
        }
    }

    @Test
    void split_cjkText_splitsAtCjkSentenceBoundaries() {
        // Chinese sentences ending with 。and ！ — use small chunk size to force split
        // Each sentence is ~6 chars = 2 tokens, so chunk size 4 forces ~2 sentences per chunk
        String text = "这是第一句话。这是第二句话！这是第三句话？这是第四句话。";
        List<String> chunks = TextChunker.split(text, 4);

        assertThat(chunks.size()).isGreaterThan(1);
        for (String chunk : chunks) {
            assertThat(chunk).isNotBlank();
        }
    }

    @Test
    void split_multipleParagraphBreaks_handlesCorrectly() {
        String text = "Para one.\n\n\n\nPara two.\n\nPara three.";
        List<String> chunks = TextChunker.split(text, 100);

        assertThat(chunks).hasSize(3);
    }

    // ---- estimateTokens (package-private) ----

    @Test
    void estimateTokens_normalString() {
        assertThat(TextChunker.estimateTokens("hello")).isEqualTo(1); // 5/3 = 1
    }

    @Test
    void estimateTokens_null_returnsZero() {
        assertThat(TextChunker.estimateTokens(null)).isEqualTo(0);
    }

    @Test
    void estimateTokens_emptyString_returnsZero() {
        assertThat(TextChunker.estimateTokens("")).isEqualTo(0);
    }

    @Test
    void estimateTokens_longString() {
        String text = "a".repeat(300);
        assertThat(TextChunker.estimateTokens(text)).isEqualTo(100); // 300/3 = 100
    }
}

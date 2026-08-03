package com.harness.agent.voice;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpeechChunkerTest {

    @Test
    void emitsStableSentenceBeforeFinalFlush() {
        SpeechChunker chunker = new SpeechChunker(6, 12, 20);

        assertThat(chunker.append("这是第一句。后")).containsExactly("这是第一句。");
        assertThat(chunker.finish()).containsExactly("后");
    }

    @Test
    void usesSoftBoundaryAndRemovesMarkdownUrl() {
        SpeechChunker chunker = new SpeechChunker(4, 8, 12);

        assertThat(chunker.append("**详细说明**，请查看 https://example.com/path 后继续"))
                .containsExactly("详细说明，", "请查看");
    }

    @Test
    void hardBoundaryDoesNotSplitSurrogatePair() {
        SpeechChunker chunker = new SpeechChunker(2, 3, 4);

        assertThat(chunker.append("甲乙丙😀后续"))
                .allSatisfy(chunk -> assertThat(chunk).doesNotContain("�"));
    }

    @Test
    void doesNotSpeakFencedCodeBeforeTheFenceCloses() {
        SpeechChunker chunker = new SpeechChunker(2, 8, 12);

        assertThat(chunker.append("说明 ```System.out.println(\"secret\");")).containsExactly("说明");
        assertThat(chunker.append("``` 后续内容。"))
                .allSatisfy(chunk -> assertThat(chunk).doesNotContain("System.out", "secret"));
    }
}

package com.harness.input.multimodal;

import com.harness.core.text.TextTokenEstimator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextChunkerTest {

    private static final TextTokenEstimator CODE_POINT_ESTIMATOR = new TextTokenEstimator() {
        @Override
        public int estimate(String text) {
            return text == null ? 0 : text.codePointCount(0, text.length());
        }

        @Override
        public String strategyName() {
            return "test-code-points";
        }
    };

    private final TextChunker chunker = new TextChunker(CODE_POINT_ESTIMATOR);

    @Test
    void returnsNoChunksForMissingContent() {
        assertThat(chunker.chunk(null, 100)).isEmpty();
        assertThat(chunker.chunk("   \n", 100)).isEmpty();
    }

    @Test
    void bindsConsecutiveHeadingsToTheFirstBodyBlock() {
        List<MarkdownChunk> chunks = chunker.chunk("""
                # 上传文件

                ## 大小限制

                单个文件最大 20 MB。
                """, 100);

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.content()).isEqualTo(
                    "# 上传文件\n\n## 大小限制\n\n单个文件最大 20 MB。");
            assertThat(chunk.headingPath()).containsExactly("上传文件", "大小限制");
            assertThat(chunk.startBlockIndex()).isZero();
            assertThat(chunk.endBlockIndex()).isEqualTo(2);
        });
    }

    @Test
    void bindsHeadingToFollowingListAndKeepsListLinesTogetherWhenTheyFit() {
        List<MarkdownChunk> chunks = chunker.chunk("""
                ## 支持格式

                - PDF
                - Markdown
                - Word
                """, 100);

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.content()).contains("## 支持格式", "- PDF", "- Word");
            assertThat(chunk.headingPath()).containsExactly("支持格式");
        });
    }

    @Test
    void treatsHorizontalRulesAsBoundariesWithoutProducingRuleChunks() {
        List<MarkdownChunk> chunks = chunker.chunk("""
                第一节。

                ---

                第二节。

                ***

                第三节。

                ===
                """, 100);

        assertThat(chunks).extracting(MarkdownChunk::content)
                .containsExactly("第一节。", "第二节。", "第三节。");
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.content()).doesNotMatch("(?s)^\\s*(?:[-*_ =]\\s*){3,}\\s*$"));
    }

    @Test
    void doesNotParseMarkdownMarkersInsideFencedCode() {
        String markdown = """
                ```java
                # not-a-heading
                ---
                System.out.println("🙂");
                ```
                """;

        assertThat(chunker.chunk(markdown, 1000))
                .singleElement()
                .extracting(MarkdownChunk::content)
                .isEqualTo(markdown.strip());
    }

    @Test
    void splitsOversizedCodeByLineAndRetainsFencesOnEveryChunk() {
        List<MarkdownChunk> chunks = chunker.chunk("""
                ```sql
                SELECT id, display_name FROM users;
                WHERE tenant_id = '000000';
                ```
                """, 28);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.content()).startsWith("```sql").endsWith("```");
            assertThat(chunk.tokenCount()).isLessThanOrEqualTo(28);
        });
    }

    @Test
    void splitsOversizedTableByRowsAndRepeatsItsHeader() {
        List<MarkdownChunk> chunks = chunker.chunk("""
                | id | name |
                |---:|:-----|
                | 1 | Alice |
                | 2 | Bob |
                | 3 | Carol |
                """, 45);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.content()).startsWith("| id | name |\n|---:|:-----|");
            assertThat(chunk.tokenCount()).isLessThanOrEqualTo(45);
        });
    }

    @Test
    void splitsOneOversizedTableRowWithoutBreakingTheTableEnvelope() {
        List<MarkdownChunk> chunks = chunker.chunk("""
                | id | payload |
                |---:|:--------|
                | 1 | abcdefghijklmnopqrstuvwxyz |
                """, 45);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.content()).startsWith("| id | payload |\n|---:|:--------|");
            assertThat(chunk.tokenCount()).isLessThanOrEqualTo(45);
        });
    }

    @Test
    void enforcesExactUnderAndOverBudgetBoundaries() {
        assertThat(chunker.chunk("1234", 5)).singleElement()
                .extracting(MarkdownChunk::tokenCount).isEqualTo(4);
        assertThat(chunker.chunk("12345", 5)).singleElement()
                .extracting(MarkdownChunk::tokenCount).isEqualTo(5);

        List<MarkdownChunk> overBudget = chunker.chunk("123456", 5);
        assertThat(overBudget).extracting(MarkdownChunk::content)
                .containsExactly("12345", "6");
        assertThat(overBudget).allSatisfy(chunk ->
                assertThat(chunk.tokenCount()).isLessThanOrEqualTo(5));
    }

    @Test
    void greedilyMergesAdjacentBlocksWhenCombinedContentFits() {
        List<MarkdownChunk> chunks = chunker.chunk("alpha\n\nbeta", 11);

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.content()).isEqualTo("alpha\n\nbeta");
            assertThat(chunk.startBlockIndex()).isZero();
            assertThat(chunk.endBlockIndex()).isEqualTo(1);
            assertThat(chunk.tokenCount()).isEqualTo(11);
        });
    }

    @Test
    void keepsQuestionAndAnswerTogetherInKnowledgeCorpus() {
        List<MarkdownChunk> chunks = chunker.chunk("""
                ## 大小限制

                单个上传文件的大小限制是多少？

                单个上传文件最大 20 MB，超过限制会明确返回错误。
                """, 200);

        assertThat(chunks).singleElement().satisfies(chunk ->
                assertThat(chunk.content())
                        .contains("单个上传文件的大小限制是多少？")
                        .contains("单个上传文件最大 20 MB"));
    }

    @Test
    void handlesMixedLanguagesEmojiUrlsIdentifiersJsonJavaAndSqlInOrder() {
        String markdown = """
                English 与中文 mixed 🙂 https://example.com/a?q=1

                UUID: 123e4567-e89b-12d3-a456-426614174000

                JSON: {"name":"Cyrene"}

                Java: record User(String displayName) {}

                SQL: SELECT * FROM users ORDER BY id;
                """;

        List<MarkdownChunk> chunks = chunker.chunk(markdown, 1000);

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.content()).isEqualTo(markdown.strip());
            assertThat(chunk.tokenCount()).isEqualTo(CODE_POINT_ESTIMATOR.estimate(markdown.strip()));
        });
    }

    @Test
    void staticCompatibilityApiUsesUnicodeAwareEstimator() {
        assertThat(TextChunker.split("hello 世界 🙂", 100))
                .containsExactly("hello 世界 🙂");
        assertThat(TextChunker.estimateTokens(null)).isZero();
        assertThat(TextChunker.estimateTokens("hello 世界 🙂")).isPositive();
    }

    @Test
    void neverProducesContentForHorizontalRuleOnlyDocuments() {
        assertThat(chunker.chunk("---\n\n***\n\n===", 100)).isEmpty();
    }
}

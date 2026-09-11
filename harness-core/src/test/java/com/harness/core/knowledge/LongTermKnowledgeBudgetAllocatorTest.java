package com.harness.core.knowledge;

import com.harness.core.text.TextTokenEstimator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LongTermKnowledgeBudgetAllocatorTest {

    @Test
    void budget_isExactlyThreePercentForDocumentedContextWindows() {
        LongTermKnowledgeBudgetAllocator allocator = new LongTermKnowledgeBudgetAllocator(fixedEstimator(1));

        assertThat(allocator.allocate(1_000_000, List.of()).budgetTokens()).isEqualTo(30_000);
        assertThat(allocator.allocate(128_000, List.of()).budgetTokens()).isEqualTo(3_840);
        assertThat(allocator.allocate(32_000, List.of()).budgetTokens()).isEqualTo(960);
    }

    @Test
    void allocation_keepsBlocksWholeAndSharesOneBudgetAcrossMemoryTypes() {
        KnowledgeContextBlock preference = block(KnowledgeConceptType.USER_PREFERENCE, "preference");
        KnowledgeContextBlock episode = block(KnowledgeConceptType.USER_EPISODE, "episode");
        KnowledgeContextBlock playbook = block(KnowledgeConceptType.OPERATION_PLAYBOOK, "playbook");
        LongTermKnowledgeBudgetAllocator allocator = new LongTermKnowledgeBudgetAllocator(fixedEstimator(40));

        LongTermKnowledgeBudgetAllocator.Allocation allocation = allocator.allocate(
                3_000,
                List.of(preference, episode, playbook));

        assertThat(allocation.budgetTokens()).isEqualTo(90);
        assertThat(allocation.usedTokens()).isEqualTo(80);
        assertThat(allocation.selectedBlocks()).containsExactly(preference, episode);
        assertThat(allocation.skippedBlocks()).containsExactly(playbook);
        assertThat(allocation.renderedContext()).contains(preference.content(), episode.content());
        assertThat(allocation.renderedContext()).doesNotContain(playbook.content());
    }

    @Test
    void honorsConfiguredBudgetRatio() {
        LongTermKnowledgeBudgetAllocator allocator =
                new LongTermKnowledgeBudgetAllocator(fixedEstimator(1), 0.05);

        assertThat(allocator.allocate(1_000, List.of()).budgetTokens()).isEqualTo(50);
        assertThatThrownBy(() -> new LongTermKnowledgeBudgetAllocator(
                fixedEstimator(1), 1.01))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budgetRatio");
    }

    private static KnowledgeContextBlock block(KnowledgeConceptType type, String marker) {
        return new KnowledgeContextBlock(
                type,
                "concept-" + marker,
                "revision-" + marker,
                marker,
                "complete-" + marker + "-content",
                "relevant");
    }

    private static TextTokenEstimator fixedEstimator(int tokens) {
        return new TextTokenEstimator() {
            @Override
            public int estimate(String text) {
                return tokens;
            }

            @Override
            public String strategyName() {
                return "fixed-test";
            }
        };
    }
}

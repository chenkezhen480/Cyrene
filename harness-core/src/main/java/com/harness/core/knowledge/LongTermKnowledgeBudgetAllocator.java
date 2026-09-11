package com.harness.core.knowledge;

import com.harness.core.text.TextTokenEstimator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Packs already-ranked, eligible long-term knowledge without truncating a block. */
public final class LongTermKnowledgeBudgetAllocator {

    public static final double DEFAULT_BUDGET_RATIO = 0.03;

    private final TextTokenEstimator tokenEstimator;
    private final double budgetRatio;

    public LongTermKnowledgeBudgetAllocator(TextTokenEstimator tokenEstimator) {
        this(tokenEstimator, DEFAULT_BUDGET_RATIO);
    }

    public LongTermKnowledgeBudgetAllocator(
            TextTokenEstimator tokenEstimator,
            double budgetRatio
    ) {
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator");
        if (!Double.isFinite(budgetRatio) || budgetRatio < 0 || budgetRatio > 1) {
            throw new IllegalArgumentException("budgetRatio must be between 0 and 1");
        }
        this.budgetRatio = budgetRatio;
    }

    public Allocation allocate(long contextWindowTokens, List<KnowledgeContextBlock> candidates) {
        if (contextWindowTokens < 0) {
            throw new IllegalArgumentException("contextWindowTokens must not be negative");
        }
        double calculated = Math.floor(contextWindowTokens * budgetRatio);
        if (calculated > Long.MAX_VALUE) {
            throw new IllegalArgumentException("long-term knowledge budget exceeds supported size");
        }
        long calculatedBudget = (long) calculated;
        if (calculatedBudget > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("long-term knowledge budget exceeds supported size");
        }
        int budgetTokens = (int) calculatedBudget;
        List<KnowledgeContextBlock> selected = new ArrayList<>();
        List<KnowledgeContextBlock> skipped = new ArrayList<>();
        int usedTokens = 0;
        for (KnowledgeContextBlock candidate : candidates == null ? List.<KnowledgeContextBlock>of() : candidates) {
            Objects.requireNonNull(candidate, "knowledge context candidate");
            int blockTokens = tokenEstimator.estimate(candidate.render());
            if (blockTokens < 0) {
                throw new IllegalStateException("token estimator returned a negative value");
            }
            if (blockTokens <= budgetTokens - usedTokens) {
                selected.add(candidate);
                usedTokens += blockTokens;
            } else {
                skipped.add(candidate);
            }
        }
        return new Allocation(budgetTokens, usedTokens, selected, skipped);
    }

    public record Allocation(
            int budgetTokens,
            int usedTokens,
            List<KnowledgeContextBlock> selectedBlocks,
            List<KnowledgeContextBlock> skippedBlocks
    ) {
        public Allocation {
            if (budgetTokens < 0 || usedTokens < 0 || usedTokens > budgetTokens) {
                throw new IllegalArgumentException("invalid knowledge budget allocation");
            }
            selectedBlocks = List.copyOf(selectedBlocks);
            skippedBlocks = List.copyOf(skippedBlocks);
        }

        public String renderedContext() {
            return selectedBlocks.stream()
                    .map(KnowledgeContextBlock::render)
                    .reduce((left, right) -> left + "\n\n" + right)
                    .orElse("");
        }
    }
}

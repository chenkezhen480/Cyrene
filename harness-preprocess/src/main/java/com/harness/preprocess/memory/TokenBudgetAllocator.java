package com.harness.preprocess.memory;

import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dynamically allocates token budgets for different context parts.
 *
 * Base ratios (configurable via env vars):
 *   System       ~5%
 *   Long-term    ~fixed max tokens
 *   Short-term   ~20% (base), up to 35% (dynamic max)
 *   RAG          ~30% (base)
 *   Current input = remaining
 *
 * Dynamic adjustment: if RAG uses less than its base ratio,
 * the freed space is given to short-term memory (up to dynamic max).
 */
public class TokenBudgetAllocator {

    private static final Logger log = LoggerFactory.getLogger(TokenBudgetAllocator.class);

    private final int ratioSystem;
    private final int longtermMaxTokens;
    private final int ratioShortterm;
    private final int ratioShorttermMax;
    private final int ratioRag;

    public TokenBudgetAllocator() {
        EnvConfig cfg = EnvConfig.get();
        this.ratioSystem = cfg.getInt(EnvKey.CTX_RATIO_SYSTEM, 5);
        this.longtermMaxTokens = cfg.getInt(EnvKey.MEMORY_LONGTERM_MAX_TOKENS, 800);
        this.ratioShortterm = cfg.getInt(EnvKey.CTX_RATIO_SHORTTERM, 20);
        this.ratioShorttermMax = cfg.getInt(EnvKey.CTX_RATIO_SHORTTERM_MAX, 35);
        this.ratioRag = cfg.getInt(EnvKey.CTX_RATIO_RAG, 30);
    }

    /**
     * Result of token budget allocation.
     */
    public record Budget(
            int totalTokens,
            int systemTokens,
            int longtermTokens,
            int shorttermTokens,
            int ragTokens,
            int inputTokens
    ) {}

    /**
     * Calculate token budget for the given total context window and actual RAG usage.
     *
     * @param totalTokens  total context window size
     * @param actualRagTokens actual tokens used by RAG (after retrieval)
     */
    public Budget allocate(int totalTokens, int actualRagTokens) {
        int systemTokens = (int) (totalTokens * ratioSystem / 100.0);
        int ragBase = (int) (totalTokens * ratioRag / 100.0);
        int shorttermBase = (int) (totalTokens * ratioShortterm / 100.0);
        int shorttermMax = (int) (totalTokens * ratioShorttermMax / 100.0);

        // Actual RAG usage (capped at base allocation)
        int ragUsed = Math.min(actualRagTokens, ragBase);

        // Freed space from RAG goes to short-term
        int freedFromRag = ragBase - ragUsed;
        int shorttermDynamic = Math.min(shorttermBase + freedFromRag, shorttermMax);

        // Long-term is capped at fixed max tokens
        int longtermUsed = longtermMaxTokens;

        // Remaining goes to current input
        int inputTokens = totalTokens - systemTokens - longtermUsed - shorttermDynamic - ragUsed;

        Budget budget = new Budget(
                totalTokens,
                systemTokens,
                longtermUsed,
                shorttermDynamic,
                ragUsed,
                Math.max(0, inputTokens)
        );

        log.debug("Token budget: total={}, system={}, longterm={}, shortterm={}, rag={}, input={}",
                budget.totalTokens(), budget.systemTokens(), budget.longtermTokens(),
                budget.shorttermTokens(), budget.ragTokens(), budget.inputTokens());

        return budget;
    }
}

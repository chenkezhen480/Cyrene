package com.harness.core.model;

/**
 * Provider-neutral usage data for one model call.
 *
 * <p>Provider-specific counters stay {@code null} when they are not reported;
 * zero is reserved for an explicitly observed zero value.</p>
 */
public record ModelUsage(
        Long inputTokens,
        Long cachedInputTokens,
        Long cacheWriteTokens,
        Long outputTokens,
        Long reasoningTokens,
        long llmLatencyMs,
        String promptPrefixFingerprint,
        Long toolCatalogVersion
) {

    public Double cacheHitRatio() {
        if (inputTokens == null || inputTokens <= 0 || cachedInputTokens == null) {
            return null;
        }
        return cachedInputTokens.doubleValue() / inputTokens.doubleValue();
    }

    /** Input tokens billed as non-cached, when both source counters were observed. */
    public Long uncachedInputTokens() {
        if (inputTokens == null || cachedInputTokens == null) {
            return null;
        }
        return Math.max(inputTokens - cachedInputTokens, 0);
    }

    public ModelUsage withPromptContext(String fingerprint, long catalogVersion) {
        return new ModelUsage(
                inputTokens,
                cachedInputTokens,
                cacheWriteTokens,
                outputTokens,
                reasoningTokens,
                llmLatencyMs,
                fingerprint,
                catalogVersion);
    }
}

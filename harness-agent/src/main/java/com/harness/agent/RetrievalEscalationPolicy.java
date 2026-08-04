package com.harness.agent;

import com.harness.core.model.ToolResult;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.agent.context.ContextBuilder;

/**
 * Decides whether one implicit query-rewrite escalation is justified.
 * The vector store remains authoritative for the hard document acceptance threshold.
 */
final class RetrievalEscalationPolicy {

    private final double rewriteMinScore;
    private final double acceptanceThreshold;

    RetrievalEscalationPolicy(double rewriteMinScore, double acceptanceThreshold) {
        if (rewriteMinScore < 0 || rewriteMinScore >= acceptanceThreshold) {
            throw new IllegalArgumentException(
                    "rewriteMinScore must be non-negative and lower than acceptanceThreshold");
        }
        this.rewriteMinScore = rewriteMinScore;
        this.acceptanceThreshold = acceptanceThreshold;
    }

    static RetrievalEscalationPolicy fromEnvironment() {
        EnvConfig config = EnvConfig.get();
        return new RetrievalEscalationPolicy(
                config.getDouble(EnvKey.RAG_REWRITE_MIN_SCORE, 0.3),
                config.getDouble(EnvKey.RAG_SCORE_THRESHOLD, 0.7));
    }

    ToolResult.ResultStatus evaluate(
            ContextBuilder.ContextResult result,
            boolean rewriteAttempt,
            boolean escalationAlreadyUsed
    ) {
        if (!result.hasContext()) {
            return isNearMiss(result.bestObservedScore(), rewriteAttempt, escalationAlreadyUsed)
                    ? ToolResult.ResultStatus.ESCALATING
                    : ToolResult.ResultStatus.EMPTY;
        }

        double topScore = result.topScore();
        if (topScore >= acceptanceThreshold) {
            return ToolResult.ResultStatus.SUCCESS;
        }
        if (isNearMiss(topScore, rewriteAttempt, escalationAlreadyUsed)) {
            return ToolResult.ResultStatus.ESCALATING;
        }
        return ToolResult.ResultStatus.LOW_RELEVANCE;
    }

    private boolean isNearMiss(double score, boolean rewriteAttempt, boolean escalationAlreadyUsed) {
        return !rewriteAttempt
                && !escalationAlreadyUsed
                && score >= rewriteMinScore
                && score < acceptanceThreshold;
    }
}

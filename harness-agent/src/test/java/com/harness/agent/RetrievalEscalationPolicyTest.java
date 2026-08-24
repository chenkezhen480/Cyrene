package com.harness.agent;

import com.harness.core.model.ToolResult;
import com.harness.agent.context.ContextBuilder;
import com.harness.tool.rag.RagRetriever;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalEscalationPolicyTest {

    private final RetrievalEscalationPolicy policy = new RetrievalEscalationPolicy(0.3, 0.7);

    @Test
    void directNearMissWithoutAcceptedDocumentsAllowsOneEscalation() {
        ContextBuilder.ContextResult result = resultWithoutContext(0.45);

        assertThat(policy.evaluate(result, false, false))
                .isEqualTo(ToolResult.ResultStatus.ESCALATING);
    }

    @Test
    void completelyIrrelevantResultDoesNotEscalate() {
        ContextBuilder.ContextResult result = resultWithoutContext(0.29);

        assertThat(policy.evaluate(result, false, false))
                .isEqualTo(ToolResult.ResultStatus.EMPTY);
    }

    @Test
    void rewriteAttemptCannotEscalateAgain() {
        ContextBuilder.ContextResult result = resultWithContext(0.5);

        assertThat(policy.evaluate(result, true, true))
                .isEqualTo(ToolResult.ResultStatus.LOW_RELEVANCE);
    }

    @Test
    void previousEscalationPreventsAnotherDirectEscalation() {
        ContextBuilder.ContextResult result = resultWithoutContext(0.45);

        assertThat(policy.evaluate(result, false, true))
                .isEqualTo(ToolResult.ResultStatus.EMPTY);
    }

    @Test
    void acceptedHighScoreResultSucceeds() {
        ContextBuilder.ContextResult result = resultWithContext(0.7);

        assertThat(policy.evaluate(result, false, false))
                .isEqualTo(ToolResult.ResultStatus.SUCCESS);
    }

    private static ContextBuilder.ContextResult resultWithoutContext(double bestObservedScore) {
        return new ContextBuilder.ContextResult(
                List.of(),
                Map.of(
                        "top_score", "0.0",
                        "best_observed_score", String.valueOf(bestObservedScore),
                        "observed_candidate_count", "5"));
    }

    private static ContextBuilder.ContextResult resultWithContext(double topScore) {
        return new ContextBuilder.ContextResult(
                List.of(new RagRetriever.RagDocument(
                        "doc-1", "content", "source.md", topScore)),
                Map.of(
                        "top_score", String.valueOf(topScore),
                        "best_observed_score", "0.8",
                        "observed_candidate_count", "5"));
    }
}

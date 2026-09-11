package com.harness.core.model;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReActStepTest {

    @AfterEach
    void clearCurrentSteps() {
        ReActStep.clearCurrentSteps();
    }

    @Test
    void structuredToolStatusHistoryUsesLatestResult() {
        ReActStep.setCurrentSteps(List.of(
                step(1, ResultStatus.ESCALATING),
                step(2, ResultStatus.LOW_RELEVANCE)));

        assertThat(ReActStep.getLastToolResultStatus("knowledge_search"))
                .isEqualTo(ResultStatus.LOW_RELEVANCE);
        assertThat(ReActStep.hasToolResultStatus(
                "knowledge_search", ResultStatus.ESCALATING)).isTrue();
    }

    private static ReActStep step(int number, ResultStatus status) {
        ToolResult result = ToolResult.ok(
                "call-" + number,
                "knowledge_search",
                "result",
                10,
                status);
        return new ReActStep(
                number,
                null,
                "knowledge_search",
                List.of(),
                List.of(result),
                result.output(),
                null);
    }
}

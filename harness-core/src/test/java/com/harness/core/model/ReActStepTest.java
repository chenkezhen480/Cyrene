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
                step(1, ToolResult.ResultStatus.ESCALATING),
                step(2, ToolResult.ResultStatus.LOW_RELEVANCE)));

        assertThat(ReActStep.getLastToolResultStatus("knowledge_base_search"))
                .isEqualTo(ToolResult.ResultStatus.LOW_RELEVANCE);
        assertThat(ReActStep.hasToolResultStatus(
                "knowledge_base_search", ToolResult.ResultStatus.ESCALATING)).isTrue();
    }

    private static ReActStep step(int number, ToolResult.ResultStatus status) {
        ToolResult result = ToolResult.ok(
                "call-" + number,
                "knowledge_base_search",
                "result",
                10,
                status);
        return new ReActStep(
                number,
                null,
                "knowledge_base_search",
                List.of(),
                List.of(result),
                result.output(),
                null);
    }
}

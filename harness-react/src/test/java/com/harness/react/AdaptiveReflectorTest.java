package com.harness.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.ReActStep;
import com.harness.core.model.ToolCall;
import com.harness.core.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveReflectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void fifthFailureReflectsAndSixthFailureReachesHardLimit() {
        AdaptiveReflector reflector = new AdaptiveReflector(5);
        List<ReActStep> steps = new ArrayList<>();
        ReActStep.InspectionResult inspection = new ReActStep.InspectionResult(
                ReActStep.InspectionResult.InspectionStatus.TOOL_ERROR,
                "invalid graph parameters");

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(evaluate(reflector, steps, inspection, attempt)).isNull();
        }

        AdaptiveReflector.ReflectionSignal reflection =
                evaluate(reflector, steps, inspection, 5);
        assertThat(reflection).isNotNull();
        assertThat(reflection.hardLimit()).isFalse();

        AdaptiveReflector.ReflectionSignal hardLimit =
                evaluate(reflector, steps, inspection, 6);
        assertThat(hardLimit).isNotNull();
        assertThat(hardLimit.hardLimit()).isTrue();
        assertThat(hardLimit.prompt()).contains("failed 6 consecutive times");
    }

    private static AdaptiveReflector.ReflectionSignal evaluate(
            AdaptiveReflector reflector,
            List<ReActStep> steps,
            ReActStep.InspectionResult inspection,
            int attempt
    ) {
        ToolCall call = new ToolCall(
                "call-" + attempt,
                "knowledge_graph_search",
                MAPPER.createObjectNode().put("graphId", "wrong"));
        ToolResult result = ToolResult.fail(
                call.id(), call.toolName(), "invalid graph parameters", 1);
        steps.add(new ReActStep(
                attempt,
                null,
                call.toolName(),
                List.of(call),
                List.of(result),
                "",
                inspection));
        return reflector.shouldReflect(
                inspection,
                List.of(call),
                List.of(result),
                steps,
                "must query the graph");
    }
}

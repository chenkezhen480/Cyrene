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
    void reflectsOnEveryFailureUpToTheBudgetThenHardStops() {
        AdaptiveReflector reflector = new AdaptiveReflector(5);
        List<ReActStep> steps = new ArrayList<>();
        ReActStep.InspectionResult inspection = new ReActStep.InspectionResult(
                ReActStep.InspectionResult.InspectionStatus.TOOL_ERROR,
                "invalid graph parameters");

        // 每次失败都注入反思，模型不会再有「闷头连败」的窗口。
        for (int attempt = 1; attempt <= 5; attempt++) {
            AdaptiveReflector.ReflectionSignal reflection =
                    evaluate(reflector, steps, inspection, attempt);

            assertThat(reflection).isNotNull();
            assertThat(reflection.hardLimit()).isFalse();
            // 首次失败无从比较参数，之后同参数连败走 stuck 分支。
            assertThat(reflection.prompt()).contains(attempt == 1
                    ? "has failed 1 consecutive times"
                    : "has been called " + attempt + " times in a row");
        }

        // 预算（5 次反思）用尽后，再失败一次即硬停。
        AdaptiveReflector.ReflectionSignal hardLimit =
                evaluate(reflector, steps, inspection, 6);
        assertThat(hardLimit).isNotNull();
        assertThat(hardLimit.hardLimit()).isTrue();
        assertThat(hardLimit.prompt()).contains("failed 6 consecutive times");
    }

    @Test
    void aSuccessfulCallClearsTheFailureCounter() {
        AdaptiveReflector reflector = new AdaptiveReflector(2);
        List<ReActStep> steps = new ArrayList<>();
        ReActStep.InspectionResult inspection = new ReActStep.InspectionResult(
                ReActStep.InspectionResult.InspectionStatus.TOOL_ERROR,
                "invalid graph parameters");

        assertThat(evaluate(reflector, steps, inspection, 1).hardLimit()).isFalse();
        assertThat(evaluate(reflector, steps, inspection, 2).hardLimit()).isFalse();

        // 失败 3 次本会硬停，但中间成功一次把计数清零，重新获得完整预算。
        ToolCall call = new ToolCall(
                "call-ok", "knowledge_graph_search", MAPPER.createObjectNode());
        ToolResult success = ToolResult.ok(call.id(), call.toolName(), "ok", 1);
        steps.add(new ReActStep(
                3, null, call.toolName(), List.of(call), List.of(success), "", inspection));
        assertThat(reflector.shouldReflect(
                inspection, List.of(call), List.of(success), steps, "must query the graph"))
                .isNull();

        assertThat(evaluate(reflector, steps, inspection, 4).hardLimit()).isFalse();
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

    /**
     * A merged tool stands in for several capabilities, so its failures are counted per action.
     * Three different actions each failing once is three pieces of work going wrong, not one tool
     * failing three times in a row.
     */
    @Test
    void aMergedToolCountsEachActionSeparately() {
        AdaptiveReflector reflector = new AdaptiveReflector(2);
        List<ReActStep> steps = new ArrayList<>();

        for (String action : List.of("read", "glob", "grep")) {
            AdaptiveReflector.ReflectionSignal signal = failAction(reflector, steps, action);
            assertThat(signal.hardLimit()).as("action %s", action).isFalse();
        }
    }

    @Test
    void repeatingOneActionOfAMergedToolStillHardStops() {
        AdaptiveReflector reflector = new AdaptiveReflector(2);
        List<ReActStep> steps = new ArrayList<>();

        assertThat(failAction(reflector, steps, "read").hardLimit()).isFalse();
        assertThat(failAction(reflector, steps, "read").hardLimit()).isFalse();
        // Budget spent on `read`; the next `read` failure still ends tool planning.
        assertThat(failAction(reflector, steps, "read").hardLimit()).isTrue();
    }

    private static AdaptiveReflector.ReflectionSignal failAction(
            AdaptiveReflector reflector,
            List<ReActStep> steps,
            String action
    ) {
        ToolCall call = new ToolCall(
                "call-" + steps.size(),
                "code_workspace",
                MAPPER.createObjectNode().put("action", action));
        ToolResult result = ToolResult.fail(
                call.id(), call.toolName(), "no such file", 1);
        ReActStep.InspectionResult inspection = new ReActStep.InspectionResult(
                ReActStep.InspectionResult.InspectionStatus.TOOL_ERROR,
                "no such file");
        steps.add(new ReActStep(
                steps.size() + 1,
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
                "look at the code");
    }
}

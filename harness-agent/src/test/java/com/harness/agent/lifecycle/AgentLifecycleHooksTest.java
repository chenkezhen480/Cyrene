package com.harness.agent.lifecycle;

import com.harness.core.model.AgentContext;
import com.harness.input.gap.GapAnalysis;
import com.harness.input.gap.GapAnalyzer;
import com.harness.react.ReActResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentLifecycleHooksTest {

    @Test
    void executesBeforeLoopAndBeforeFinalHooksInRegistrationOrder() {
        List<String> calls = new ArrayList<>();
        GapAnalysis analysis = GapAnalysis.defaults();
        ReActResult result = new ReActResult("done", List.of());
        AgentLifecycleHooks hooks = new AgentLifecycleHooks(
                List.of(
                        context -> {
                            calls.add("loop-1");
                            return context.withGapAnalysis(analysis);
                        },
                        context -> {
                            calls.add("loop-2");
                            return context;
                        }),
                List.of(
                        context -> {
                            calls.add("final-1");
                            return context;
                        },
                        context -> {
                            calls.add("final-2");
                            return context;
                        }));

        assertThat(hooks.beforeLoop(new AgentLifecycleHooks.BeforeLoopContext(
                "input", AgentContext.empty())).gapAnalysis()).isSameAs(analysis);
        assertThat(hooks.beforeFinal(new AgentLifecycleHooks.BeforeFinalContext(
                "session", result)).result()).isSameAs(result);
        assertThat(calls).containsExactly("loop-1", "loop-2", "final-1", "final-2");
    }

    @Test
    void autoRoutingHookPreservesGapAnalyzerResult() {
        GapAnalyzer analyzer = mock(GapAnalyzer.class);
        AgentContext agentContext = AgentContext.empty();
        GapAnalysis expected = new GapAnalysis(true, false, true, "rule");
        when(analyzer.analyze("input", agentContext)).thenReturn(expected);

        AgentLifecycleHooks hooks = new AgentLifecycleHooks(
                List.of(new AutoRoutingHook(analyzer)), List.of());

        assertThat(hooks.beforeLoop(new AgentLifecycleHooks.BeforeLoopContext(
                "input", agentContext)).gapAnalysis()).isSameAs(expected);
    }

    @Test
    void hookExceptionFailsTheRun() {
        AgentLifecycleHooks hooks = new AgentLifecycleHooks(
                List.of(context -> {
                    throw new IllegalStateException("routing failed");
                }),
                List.of());

        assertThatThrownBy(() -> hooks.beforeLoop(
                new AgentLifecycleHooks.BeforeLoopContext(
                        "input", AgentContext.empty())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("routing failed");
    }

    @Test
    void beforeFinalHookExceptionFailsTheRun() {
        AgentLifecycleHooks hooks = new AgentLifecycleHooks(
                List.of(context -> context.withGapAnalysis(GapAnalysis.defaults())),
                List.of(context -> {
                    throw new IllegalStateException("final validation failed");
                }));

        assertThatThrownBy(() -> hooks.beforeFinal(
                new AgentLifecycleHooks.BeforeFinalContext(
                        "session", new ReActResult("done", List.of()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("final validation failed");
    }
}

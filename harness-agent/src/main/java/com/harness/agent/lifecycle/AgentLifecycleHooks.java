package com.harness.agent.lifecycle;

import com.harness.core.model.AgentContext;
import com.harness.input.gap.GapAnalysis;
import com.harness.react.ReActResult;

import java.util.List;
import java.util.Objects;

/** Strongly typed hook slots around the existing Agent loop lifecycle. */
public final class AgentLifecycleHooks {

    @FunctionalInterface
    public interface BeforeLoopHook {
        BeforeLoopContext execute(BeforeLoopContext context);
    }

    @FunctionalInterface
    public interface BeforeFinalHook {
        BeforeFinalContext execute(BeforeFinalContext context);
    }

    public record BeforeLoopContext(
            String input,
            AgentContext agentContext,
            GapAnalysis gapAnalysis
    ) {
        public BeforeLoopContext {
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(agentContext, "agentContext");
        }

        public BeforeLoopContext(String input, AgentContext agentContext) {
            this(input, agentContext, null);
        }

        public BeforeLoopContext withGapAnalysis(GapAnalysis analysis) {
            return new BeforeLoopContext(input, agentContext,
                    Objects.requireNonNull(analysis, "analysis"));
        }
    }

    public record BeforeFinalContext(
            String sessionId,
            ReActResult result
    ) {
        public BeforeFinalContext {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(result, "result");
        }

        public BeforeFinalContext withResult(ReActResult finalResult) {
            return new BeforeFinalContext(sessionId,
                    Objects.requireNonNull(finalResult, "finalResult"));
        }
    }

    private final List<BeforeLoopHook> beforeLoopHooks;
    private final List<BeforeFinalHook> beforeFinalHooks;

    public AgentLifecycleHooks(
            List<BeforeLoopHook> beforeLoopHooks,
            List<BeforeFinalHook> beforeFinalHooks
    ) {
        this.beforeLoopHooks = List.copyOf(beforeLoopHooks);
        this.beforeFinalHooks = List.copyOf(beforeFinalHooks);
    }

    public BeforeLoopContext beforeLoop(BeforeLoopContext initial) {
        BeforeLoopContext current = Objects.requireNonNull(initial, "initial");
        for (BeforeLoopHook hook : beforeLoopHooks) {
            current = Objects.requireNonNull(hook.execute(current),
                    "BeforeLoopHook result");
        }
        if (current.gapAnalysis() == null) {
            throw new IllegalStateException("BeforeLoop hooks did not produce GapAnalysis");
        }
        return current;
    }

    public BeforeFinalContext beforeFinal(BeforeFinalContext initial) {
        BeforeFinalContext current = Objects.requireNonNull(initial, "initial");
        for (BeforeFinalHook hook : beforeFinalHooks) {
            current = Objects.requireNonNull(hook.execute(current),
                    "BeforeFinalHook result");
        }
        return current;
    }
}

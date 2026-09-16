package com.harness.agent.lifecycle;

import com.harness.input.gap.GapAnalyzer;

import java.util.Objects;

/** Preserves the existing GapAnalyzer routing behavior in the Before Loop slot. */
public final class AutoRoutingHook implements AgentLifecycleHooks.BeforeLoopHook {

    private final GapAnalyzer gapAnalyzer;

    public AutoRoutingHook(GapAnalyzer gapAnalyzer) {
        this.gapAnalyzer = Objects.requireNonNull(gapAnalyzer, "gapAnalyzer");
    }

    @Override
    public AgentLifecycleHooks.BeforeLoopContext execute(
            AgentLifecycleHooks.BeforeLoopContext context
    ) {
        return context.withGapAnalysis(
                gapAnalyzer.analyze(context.input(), context.agentContext()));
    }
}

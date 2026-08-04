package com.harness.agent.runtime;

import com.harness.provider.ModelProviders;
import com.harness.react.ReActLoop;
import com.harness.react.ReActLoopFactory;
import com.harness.core.runtime.RunTrace;
import com.harness.core.runtime.RunTraceFactory;
import com.harness.input.InputStage;
import com.harness.tool.ToolCatalog;
import com.harness.tool.ToolExecutor;

import java.util.Objects;

/**
 * Stable runtime composition for Provider -> Input -> ReAct loop -> Trace.
 *
 * <p>The orchestrator owns request/session policy; this object owns the four
 * execution abstractions and their lifecycle boundaries.</p>
 */
public record AgentRuntime(
        ModelProviders providers,
        InputStage input,
        ReActLoopFactory reActLoops,
        RunTraceFactory traces
) {
    public AgentRuntime {
        Objects.requireNonNull(providers, "providers");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(reActLoops, "reActLoops");
        Objects.requireNonNull(traces, "traces");
    }

    public RunTrace startTrace() {
        return traces.start();
    }

    public ReActLoop createLoop(ToolCatalog toolCatalog, ToolExecutor toolExecutor) {
        return reActLoops.create(toolCatalog, toolExecutor);
    }
}

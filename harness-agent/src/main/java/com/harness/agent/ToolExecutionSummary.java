package com.harness.agent;

import java.util.Map;

/** Aggregate tool outcomes returned to the parent without copying ReAct steps. */
public record ToolExecutionSummary(
        int totalExecutions,
        Map<String, ToolExecutionStats> tools
) {
    public ToolExecutionSummary {
        tools = tools == null ? Map.of() : Map.copyOf(tools);
    }

    public static ToolExecutionSummary empty() {
        return new ToolExecutionSummary(0, Map.of());
    }

    public record ToolExecutionStats(
            int attemptCount,
            int successfulCount,
            int failedCount,
            String latestError
    ) {}
}

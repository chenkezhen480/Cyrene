package com.harness.react;

/** Quality and resource signals captured during a ReAct loop. */
public record ReActLoopStats(
        String outcome,
        int rounds,
        int toolCalls,
        int reflectionChecks,
        long inputTokens,
        long outputTokens,
        int llmCalls,
        int toolRetries
) {
}

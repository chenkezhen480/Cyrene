package com.harness.agent.experiment;

import com.harness.core.model.AgentTrace;

final class ExperimentUsage {
    static Long observedTotalTokens(AgentTrace trace) {
        String observedCalls = trace.metadata().get("llmUsageCallCount");
        if (observedCalls == null) return null;
        int calls = Integer.parseInt(observedCalls);
        if (calls < 1) return null;
        long total = 0;
        for (int call = 1; call <= calls; call++) {
            String input = trace.metadata().get("llmUsageCall" + call + "InputTokens");
            String output = trace.metadata().get("llmUsageCall" + call + "OutputTokens");
            if (input == null || output == null) return null;
            total = Math.addExact(total, Math.addExact(Long.parseLong(input), Long.parseLong(output)));
        }
        return total;
    }
}

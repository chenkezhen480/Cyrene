package com.harness.core.model;

import java.util.List;

/**
 * Final result returned by the agent to the caller.
 */
public record AgentResult(
        String output,
        RiskLevel riskLevel,
        boolean requiresConfirmation,
        List<ReActStep> steps,
        AgentTrace trace
) {
    public static AgentResult success(String output, AgentTrace trace, List<ReActStep> steps) {
        return new AgentResult(output, trace.riskLevel(), false, steps, trace);
    }

    public static AgentResult needConfirmation(String output, RiskLevel risk, AgentTrace trace, List<ReActStep> steps) {
        return new AgentResult(output, risk, true, steps, trace);
    }
}

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
        AgentTrace trace,
        List<Artifact> artifacts
) {
    public AgentResult {
        if (artifacts == null) artifacts = List.of();
    }

    public static AgentResult success(String output, AgentTrace trace, List<ReActStep> steps) {
        return new AgentResult(output, trace.riskLevel(), false, steps, trace, List.of());
    }

    public static AgentResult success(String output, AgentTrace trace, List<ReActStep> steps, List<Artifact> artifacts) {
        return new AgentResult(output, trace.riskLevel(), false, steps, trace, artifacts);
    }

    public static AgentResult needConfirmation(String output, RiskLevel risk, AgentTrace trace, List<ReActStep> steps) {
        return new AgentResult(output, risk, true, steps, trace, List.of());
    }

    public static AgentResult needConfirmation(String output, RiskLevel risk, AgentTrace trace,
                                               List<ReActStep> steps, List<Artifact> artifacts) {
        return new AgentResult(output, risk, true, steps, trace, artifacts);
    }
}

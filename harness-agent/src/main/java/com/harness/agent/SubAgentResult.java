package com.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.harness.core.model.Artifact;

import java.util.List;

/**
 * Result returned by a sub-agent after completing its task.
 */
public record SubAgentResult(
        String taskId,
        String output,
        String error,
        boolean success,
        SubAgentStatus status,
        List<Artifact> artifacts,
        ToolExecutionSummary toolExecutionSummary,
        ContractValidation contractValidation,
        JsonNode structuredOutput,
        long durationMs,
        String traceId
) {
    public SubAgentResult {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        toolExecutionSummary = toolExecutionSummary == null
                ? ToolExecutionSummary.empty()
                : toolExecutionSummary;
        contractValidation = contractValidation == null
                ? ContractValidation.notDeclared()
                : contractValidation;
        structuredOutput = structuredOutput == null ? null : structuredOutput.deepCopy();
    }

    public static SubAgentResult success(
            String taskId,
            String output,
            SubAgentCompletionContractValidator.Evaluation evaluation,
            long durationMs,
            String traceId
    ) {
        return new SubAgentResult(
                taskId, output, null, true, SubAgentStatus.SUCCEEDED,
                evaluation.artifacts(), evaluation.toolExecutionSummary(),
                evaluation.contractValidation(), evaluation.structuredOutput(),
                durationMs, traceId);
    }

    public static SubAgentResult incomplete(
            String taskId,
            String output,
            SubAgentCompletionContractValidator.Evaluation evaluation,
            long durationMs,
            String traceId
    ) {
        return new SubAgentResult(
                taskId, output, "Completion contract was not satisfied", false,
                SubAgentStatus.INCOMPLETE, evaluation.artifacts(),
                evaluation.toolExecutionSummary(), evaluation.contractValidation(),
                evaluation.structuredOutput(), durationMs, traceId);
    }

    public static SubAgentResult failure(
            String taskId,
            String error,
            long durationMs,
            boolean contractDeclared
    ) {
        return new SubAgentResult(
                taskId, null, error, false, SubAgentStatus.FAILED, List.of(),
                ToolExecutionSummary.empty(),
                ContractValidation.notEvaluated(contractDeclared), null,
                durationMs, null);
    }
}

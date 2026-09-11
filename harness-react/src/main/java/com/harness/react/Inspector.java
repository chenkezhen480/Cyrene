package com.harness.react;

import com.harness.core.model.ExecutionStatus;
import com.harness.core.model.ReActStep.InspectionResult;
import com.harness.core.model.ReActStep.InspectionResult.InspectionStatus;
import com.harness.core.model.ResultStatus;
import com.harness.core.model.ToolCall;
import com.harness.core.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * Post-tool-execution inspector that evaluates tool call results.
 * Assigns an InspectionStatus (PASS, TOOL_ERROR, WRONG_TOOL, INSUFFICIENT)
 * with a human-readable reason.
 *
 * All inspection is heuristic-based (no model calls). If inspection itself fails,
 * defaults to PASS to avoid blocking the ReAct loop.
 */
public class Inspector {

    private static final Logger log = LoggerFactory.getLogger(Inspector.class);

    /** Phrases that indicate the tool found nothing useful. */
    private static final Set<String> INSUFFICIENT_PHRASES = Set.of(
            "no results found",
            "no results",
            "not found",
            "not available",
            "no data",
            "empty result",
            "no matches",
            "no information",
            "nothing found",
            "0 results",
            "no entries",
            "no records",
            "no files found",
            "no files match",
            "no matching files",
            "no files matched",
            "0 files",
            "found 0 files",
            "0 match",
            "found 0 match"
    );

    /**
     * Inspect a set of tool calls and their results.
     *
     * @param toolCalls  the tool calls requested by the LLM
     * @param toolResults the results from executing those tool calls
     * @return an InspectionResult with status and reason
     */
    public InspectionResult inspect(List<ToolCall> toolCalls, List<ToolResult> toolResults) {
        try {
            return doInspect(toolCalls, toolResults);
        } catch (Exception e) {
            log.warn("[Inspector] Inspection failed, defaulting to PASS: {}", e.getMessage());
            return new InspectionResult(InspectionStatus.PASS, "inspection error, defaulting to pass");
        }
    }

    private InspectionResult doInspect(List<ToolCall> toolCalls, List<ToolResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return new InspectionResult(InspectionStatus.PASS, "no tool results to inspect");
        }

        InspectionResult confirmation = null;
        InspectionResult toolError = null;
        InspectionResult insufficient = null;
        InspectionResult wrongTool = null;

        for (ToolResult result : toolResults) {
            InspectionResult executionInspection = inspectExecution(result);
            if (executionInspection != null) {
                if (executionInspection.status() == InspectionStatus.TOOL_ERROR
                        && toolError == null) {
                    toolError = executionInspection;
                } else if (executionInspection.status() != InspectionStatus.TOOL_ERROR
                        && confirmation == null) {
                    confirmation = executionInspection;
                }
                continue;
            }

            ResultStatus resultStatus = result.resultStatus();
            if (resultStatus == ResultStatus.EMPTY
                    || resultStatus == ResultStatus.LOW_RELEVANCE
                    || resultStatus == ResultStatus.ESCALATING
                    || resultStatus == ResultStatus.PARTIAL
                    || resultStatus == ResultStatus.CONTRACT_FAILED) {
                if (insufficient == null) {
                    insufficient = insufficientResult(result, resultStatus);
                }
                continue;
            }

            if ((result.output() == null || result.output().isBlank())
                    && resultStatus != ResultStatus.PENDING) {
                if (wrongTool == null) {
                    wrongTool = new InspectionResult(
                            InspectionStatus.WRONG_TOOL,
                            "Tool '" + result.toolName() + "' returned empty output");
                }
                continue;
            }

            if (resultStatus == ResultStatus.AVAILABLE
                    && containsInsufficientPhrase(result.output())
                    && insufficient == null) {
                insufficient = new InspectionResult(
                        InspectionStatus.INSUFFICIENT,
                        "Tool '" + result.toolName()
                                + "' reported an unverified no results response");
            }
        }

        if (confirmation != null) {
            return confirmation;
        }
        if (toolError != null) {
            return toolError;
        }
        if (insufficient != null) {
            return insufficient;
        }
        if (wrongTool != null) {
            return wrongTool;
        }
        return new InspectionResult(
                InspectionStatus.PASS, "All tool executions in the round were inspected");
    }

    private InspectionResult inspectExecution(ToolResult result) {
        ExecutionStatus executionStatus = result.executionStatus();
        return switch (executionStatus) {
            case SUCCEEDED -> null;
            case CONFIRMATION_REQUIRED -> new InspectionResult(
                    InspectionStatus.CONFIRMATION_REQUIRED,
                    errorOr(result, "explicit confirmation is required"));
            case REJECTED, CANCELLED -> new InspectionResult(
                    InspectionStatus.CONFIRMATION_REJECTED,
                    errorOr(result, "tool execution was rejected or cancelled"));
            case EXPIRED -> new InspectionResult(
                    InspectionStatus.CONFIRMATION_EXPIRED,
                    errorOr(result, "tool confirmation expired"));
            case FAILED -> {
                String errorDetail = result.error() != null ? result.error() : "unknown error";
                yield new InspectionResult(
                        InspectionStatus.TOOL_ERROR,
                        "Tool '" + result.toolName() + "' failed: " + errorDetail);
            }
        };
    }

    private InspectionResult insufficientResult(ToolResult result, ResultStatus status) {
        String detail = switch (status) {
            case ESCALATING -> "found a near-miss result eligible for one retrieval escalation";
            case EMPTY -> "found no eligible results";
            case LOW_RELEVANCE -> "found only low-relevance results";
            case PARTIAL -> "returned a partial result";
            case CONTRACT_FAILED -> "returned a failed business contract result";
            default -> throw new IllegalArgumentException("Not an insufficient status: " + status);
        };
        return new InspectionResult(
                InspectionStatus.INSUFFICIENT,
                "Tool '" + result.toolName() + "' " + detail);
    }

    private boolean containsInsufficientPhrase(String output) {
        if (output == null) {
            return false;
        }
        String lower = output.toLowerCase().strip();
        return INSUFFICIENT_PHRASES.stream().anyMatch(lower::contains);
    }

    private String errorOr(ToolResult result, String fallback) {
        return result.error() == null || result.error().isBlank() ? fallback : result.error();
    }

    /**
     * Build a context hint message to inject into the next ReAct iteration
     * when inspection returns a non-PASS status. This helps the LLM adjust its strategy.
     */
    public static String buildInspectionHint(InspectionResult result) {
        if (result == null || result.status() == InspectionStatus.PASS) {
            return null;
        }
        return switch (result.status()) {
            case TOOL_ERROR -> "[Inspection] Tool error detected: " + result.reason()
                    + ". Consider using a different tool or adjusting your approach.";
            case WRONG_TOOL -> "[Inspection] Wrong tool selected: " + result.reason()
                    + ". Consider using a different tool that is better suited for this task.";
            case INSUFFICIENT -> "[Inspection] Insufficient result: " + result.reason();
            case LOOP_DETECTED -> "[Inspection] Loop detected: " + result.reason()
                    + ". You MUST output the final answer now based on available information.";
            case CONFIRMATION_REQUIRED -> "[Inspection] Confirmation required: " + result.reason()
                    + ". Stop and wait for explicit user confirmation.";
            case CONFIRMATION_REJECTED -> "[Inspection] Tool execution rejected: " + result.reason()
                    + ". Do not retry this operation.";
            case CONFIRMATION_EXPIRED -> "[Inspection] Tool confirmation expired: " + result.reason()
                    + ". Do not retry this operation without a new user request.";
            case PASS -> null;
        };
    }

}

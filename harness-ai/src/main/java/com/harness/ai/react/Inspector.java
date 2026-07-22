package com.harness.ai.react;

import com.harness.core.model.ReActStep.InspectionResult;
import com.harness.core.model.ReActStep.InspectionResult.InspectionStatus;
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

    /** Minimum output length to not be considered insufficient. */
    private static final int MIN_OUTPUT_LENGTH = 50;

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

        // Check for tool errors first (TOOL_ERROR)
        for (ToolResult result : toolResults) {
            if (!result.success()) {
                String errorDetail = result.error() != null ? result.error() : "unknown error";
                return new InspectionResult(
                        InspectionStatus.TOOL_ERROR,
                        "Tool '" + result.toolName() + "' failed: " + errorDetail);
            }
        }

        // Check for WRONG_TOOL: null/empty output when a tool should have produced something
        for (ToolResult result : toolResults) {
            if (result.output() == null || result.output().isBlank()) {
                return new InspectionResult(
                        InspectionStatus.WRONG_TOOL,
                        "Tool '" + result.toolName() + "' returned empty output");
            }
        }

        // Check for explicit status from tools that declare their own result quality.
        // This is authoritative — no guessing needed. Tools like KnowledgeBaseTool set this
        // via ThreadLocal before returning, and ToolExecutor attaches it to ToolResult.
        for (ToolResult result : toolResults) {
            if (result.status() != null) {
                return switch (result.status()) {
                    case ESCALATING -> new InspectionResult(
                            InspectionStatus.INSUFFICIENT,
                            "Tool '" + result.toolName() + "' found no results but has auto-escalated its retrieval strategy. "
                                    + "Wait for the next iteration to see if the escalated strategy produces results.");
                    case EMPTY -> new InspectionResult(
                            InspectionStatus.INSUFFICIENT,
                            "Tool '" + result.toolName() + "' exhausted all retrieval strategies with no results. "
                                    + "Try a different tool, adjust your approach, or output the final answer with available information.");
                    case LOW_RELEVANCE -> new InspectionResult(
                            InspectionStatus.INSUFFICIENT,
                            "Tool '" + result.toolName() + "' found results but none are relevant to the query, "
                                    + "and no further escalation is available. "
                                    + "Try a different tool, rephrase your query, or output the final answer with available information.");
                    case SUCCESS -> new InspectionResult(InspectionStatus.PASS,
                            "Tool '" + result.toolName() + "' explicitly reported success");
                };
            }
        }

        // Fallback: heuristic detection for external/MCP tools that don't declare explicit status.
        // These tools return arbitrary text — we have no choice but to guess.
        for (ToolResult result : toolResults) {
            String output = result.output();
            if (output != null) {
                String lower = output.toLowerCase().strip();
                if (lower.length() < MIN_OUTPUT_LENGTH) {
                    return new InspectionResult(
                            InspectionStatus.INSUFFICIENT,
                            "Tool '" + result.toolName() + "' returned very short output (" + lower.length() + " chars)");
                }
                for (String phrase : INSUFFICIENT_PHRASES) {
                    if (lower.contains(phrase)) {
                        return new InspectionResult(
                                InspectionStatus.INSUFFICIENT,
                                "Tool '" + result.toolName() + "' found no results. "
                                        + "Do NOT retry the same tool with different parameters. "
                                        + "Either try a different tool, or output the final answer with available information.");
                    }
                }
            }
        }

        return new InspectionResult(InspectionStatus.PASS, "All tools executed successfully");
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
            case PASS -> null;
        };
    }

}

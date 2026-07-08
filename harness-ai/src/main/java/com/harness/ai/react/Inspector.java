package com.harness.ai.react;

import com.harness.core.model.ReActStep;
import com.harness.core.model.ReActStep.InspectionResult;
import com.harness.core.model.ReActStep.InspectionResult.InspectionStatus;
import com.harness.core.model.ToolCall;
import com.harness.core.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

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

        // Check for INSUFFICIENT: very short result or contains "no results" phrases
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

    /**
     * Detect consecutive calls to the same tool with the same arguments.
     * This catches cases where the LLM retries the exact same call that already
     * produced no useful results.
     *
     * @param recentSteps recent ReAct steps
     * @param threshold   consecutive same-call count to trigger
     * @return InspectionResult if detected, null otherwise
     */
    public static InspectionResult detectSameToolConsecutive(List<ReActStep> recentSteps, int threshold) {
        if (recentSteps == null || threshold <= 0 || recentSteps.size() < threshold) {
            return null;
        }

        List<ReActStep> tail = recentSteps.subList(recentSteps.size() - threshold, recentSteps.size());

        // Extract first step's tool calls signature (sorted by tool name for consistency)
        String firstSig = buildToolCallsSignature(tail.get(0).toolCalls());
        if (firstSig == null) return null;

        for (int i = 1; i < tail.size(); i++) {
            String sig = buildToolCallsSignature(tail.get(i).toolCalls());
            if (!firstSig.equals(sig)) {
                return null;  // Different tool or arguments, not a loop
            }
        }

        String toolName = tail.get(0).toolCalls().isEmpty() ? "unknown"
                : tail.get(0).toolCalls().get(0).toolName();
        log.warn("[Inspector] Same tool+args loop: '{}' called {} times with identical arguments", toolName, threshold);
        return new InspectionResult(
                InspectionStatus.LOOP_DETECTED,
                String.format("Tool '%s' called %d times with identical arguments. "
                        + "It is clearly not producing useful results. "
                        + "Stop retrying and output the final answer.", toolName, threshold));
    }

    /**
     * Build a deterministic signature string from a list of tool calls
     * (tool name + sorted arguments JSON).
     */
    private static String buildToolCallsSignature(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (ToolCall tc : toolCalls) {
            sb.append(tc.toolName()).append(':');
            JsonNode args = tc.arguments();
            sb.append(args != null ? args.toString() : "null").append('|');
        }
        return sb.toString();
    }

}

package com.harness.ai.react;

import com.harness.core.model.ReActStep;
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
 * Assigns an InspectionStatus (PASS, TOOL_ERROR, WRONG_TOOL, INSUFFICIENT, NEEDS_RETRY)
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
            "no records"
    );

    /** Minimum output length to not be considered insufficient. */
    private static final int MIN_OUTPUT_LENGTH = 50;

    /**
     * Inspect a set of tool calls and their results.
     *
     * @param toolCalls  the tool calls requested by the LLM
     * @param toolResults the results from executing those tools
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

        // Check for NEEDS_RETRY: tool threw an exception or returned a stack trace
        for (ToolResult result : toolResults) {
            if (result.output() != null && looksLikeException(result.output())) {
                return new InspectionResult(
                        InspectionStatus.NEEDS_RETRY,
                        "Tool '" + result.toolName() + "' returned an exception trace");
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
                                "Tool '" + result.toolName() + "' output indicates no useful results: '" + phrase + "'");
                    }
                }
            }
        }

        return new InspectionResult(InspectionStatus.PASS, "All tools executed successfully");
    }

    /**
     * Heuristic: does the output look like a Java/Python/JS exception or stack trace?
     */
    private boolean looksLikeException(String output) {
        String lower = output.toLowerCase();
        return lower.contains("exception")
                || lower.contains("stacktrace")
                || lower.contains("stack trace")
                || lower.contains("traceback")
                || lower.contains("at com.")
                || lower.contains("at org.")
                || lower.contains("at java.")
                || (lower.contains("error") && lower.contains("\n\tat "));
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
            case INSUFFICIENT -> "[Inspection] Insufficient result: " + result.reason()
                    + ". Try a different query, different parameters, or an alternative tool.";
            case NEEDS_RETRY -> "[Inspection] Retry needed: " + result.reason()
                    + ". Try again with adjusted parameters.";
            case LOOP_DETECTED -> "[Inspection] Loop detected: " + result.reason()
                    + ". You MUST output the final answer now based on available information.";
            case PASS -> null;
        };
    }

    /**
     * 检测连续重复的工具调用（相同工具名+相同参数）。
     *
     * @param recentSteps 最近的 ReAct 步骤列表
     * @param threshold 连续重复次数阈值
     * @return 如果检测到循环返回 InspectionResult，否则返回 null
     */
    public static InspectionResult detectLoop(List<ReActStep> recentSteps, int threshold) {
        if (recentSteps == null || recentSteps.size() < threshold) {
            return null;
        }

        // 取最近 N 步
        List<ReActStep> tail = recentSteps.subList(recentSteps.size() - threshold, recentSteps.size());

        // 检查是否所有步骤都调用了相同的工具且参数一致
        String firstAction = tail.get(0).action();
        String firstArgs = extractArgsSignature(tail.get(0).toolCalls());

        if (firstAction == null || firstAction.isBlank()) return null;

        for (int i = 1; i < tail.size(); i++) {
            String action = tail.get(i).action();
            String args = extractArgsSignature(tail.get(i).toolCalls());
            if (!firstAction.equals(action) || !firstArgs.equals(args)) {
                return null;  // 不是循环
            }
        }

        log.warn("[Inspector] Loop detected: {} consecutive calls to '{}' with same args", threshold, firstAction);
        return new InspectionResult(
                InspectionStatus.LOOP_DETECTED,
                String.format("Tool '%s' called %d times consecutively with identical parameters", firstAction, threshold));
    }

    private static String extractArgsSignature(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ToolCall tc : toolCalls) {
            sb.append(tc.toolName()).append(":").append(tc.arguments()).append(";");
        }
        return sb.toString();
    }
}

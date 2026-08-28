package com.harness.react;

import com.harness.core.model.ReActStep;
import com.harness.core.model.ToolCall;
import com.harness.core.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Signal-driven adaptive reflection mechanism for the ReAct loop.
 * Replaces the old fixed-interval reflection + LOOP_DETECTED forced exit.
 *
 * Tracks consecutive non-PASS results **per tool**. Each tool has its own
 * failure counter — one tool passing does not reset another tool's count.
 *
 * When any single tool hits the threshold, a reflection prompt is injected.
 * The prompt adapts based on whether the tool keeps being called with the
 * same arguments (stuck) or different arguments (struggling).
 *
 * Reflection gives the model one final opportunity to adjust strategy. If the
 * same tool fails once more after reflection, a hard-limit signal terminates
 * further tool planning for the run.
 */
public class AdaptiveReflector {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveReflector.class);
    private static final int DEFAULT_THRESHOLD = 5;

    private final int threshold;

    /** Per-tool consecutive non-PASS count. Key = tool name. */
    private final Map<String, Integer> toolFailureCounts = new HashMap<>();

    public AdaptiveReflector() {
        this(DEFAULT_THRESHOLD);
    }

    public AdaptiveReflector(int threshold) {
        this.threshold = threshold > 0 ? threshold : DEFAULT_THRESHOLD;
    }

    /**
     * Evaluate whether a reflection prompt should be injected.
     * Inspects each tool call's result individually — per-tool tracking.
     *
     * @param inspection current round's aggregated inspection result
     * @param toolCalls  tool calls from this round
     * @param toolResults results from this round
     * @param allSteps   all steps so far (for same-args detection)
     * @param userInput  original user question
     * @return a ReflectionSignal with prompt, or null if no reflection needed
     */
    public ReflectionSignal shouldReflect(ReActStep.InspectionResult inspection,
                                          List<ToolCall> toolCalls,
                                          List<ToolResult> toolResults,
                                          List<ReActStep> allSteps,
                                          String userInput) {
        if (toolCalls == null || toolCalls.isEmpty()) return null;

        // Update per-tool counters: SUCCESS resets, everything else increments.
        for (int i = 0; i < toolCalls.size(); i++) {
            String toolName = toolCalls.get(i).toolName();
            ToolResult result = (toolResults != null && i < toolResults.size()) ? toolResults.get(i) : null;

            if (isSuccess(result)) {
                toolFailureCounts.put(toolName, 0);
            } else {
                toolFailureCounts.merge(toolName, 1, Integer::sum);
            }
        }

        // The threshold triggers reflection; one additional failure triggers a hard stop.
        for (Map.Entry<String, Integer> entry : toolFailureCounts.entrySet()) {
            String toolName = entry.getKey();
            int failureCount = entry.getValue();
            if (failureCount >= threshold + 1) {
                String prompt = buildHardLimitPrompt(toolName, failureCount, userInput);
                log.warn("[AdaptiveReflector] Hard failure limit reached: tool '{}' failed {} consecutive times",
                        toolName, failureCount);
                return new ReflectionSignal(prompt, true);
            }
            if (failureCount == threshold) {

                // Determine if it's the same args or different args each time
                boolean stuckOnSameArgs = detectStuckOnSameArgs(toolName, allSteps, threshold);
                String prompt = buildPrompt(toolName, stuckOnSameArgs, allSteps, userInput);

                log.info("[AdaptiveReflector] Reflection triggered: tool '{}' hit {} consecutive non-PASS (stuck={})",
                        toolName, threshold, stuckOnSameArgs);
                return new ReflectionSignal(prompt, false);
            }
        }

        return null;
    }

    /**
     * Determine if a tool's recent calls all used the same arguments.
     * "Stuck" = same tool + same args → LLM is retrying blindly.
     * "Struggling" = same tool + different args → LLM is trying but failing.
     */
    private boolean detectStuckOnSameArgs(String toolName, List<ReActStep> allSteps, int window) {
        if (allSteps == null || allSteps.size() < window) return false;

        // Collect the last `window` calls to this specific tool
        List<String> recentArgs = allSteps.stream()
                .filter(s -> s.toolCalls() != null)
                .flatMap(s -> s.toolCalls().stream())
                .filter(tc -> toolName.equals(tc.toolName()))
                .map(tc -> tc.arguments() != null ? tc.arguments().toString() : "null")
                .toList();

        if (recentArgs.size() < window) return false;

        // Check if the last `window` calls all have the same args
        String first = recentArgs.get(recentArgs.size() - window);
        for (int i = recentArgs.size() - window + 1; i < recentArgs.size(); i++) {
            if (!first.equals(recentArgs.get(i))) return false;
        }
        return true;
    }

    private boolean isSuccess(ToolResult result) {
        if (result == null) return false;
        if (!result.success()) return false;
        // Explicit status from tool — authoritative
        if (result.status() != null) {
            return result.status() == ToolResult.ResultStatus.SUCCESS;
        }
        // Fallback: heuristic detection for external/MCP tools without explicit status
        String output = result.output();
        if (output == null || output.isBlank()) return false;
        String lower = output.toLowerCase().strip();
        if (lower.length() < 50) return false;
        for (String phrase : INSUFFICIENT_PHRASES) {
            if (lower.contains(phrase)) return false;
        }
        return true;
    }

    private static final java.util.Set<String> INSUFFICIENT_PHRASES = java.util.Set.of(
            "no results found", "no results", "not found", "not available",
            "no data", "empty result", "no matches", "no information",
            "nothing found", "0 results", "no entries", "no records",
            "no files found", "no files match", "no matching files",
            "no files matched", "0 files", "found 0 files", "0 match", "found 0 match"
    );

    private String buildPrompt(String toolName, boolean stuckOnSameArgs,
                                List<ReActStep> allSteps, String userInput) {
        String toolSummary = summarizeToolsUsed(allSteps);

        if (stuckOnSameArgs) {
            return String.format("""
                    [System Reflection]
                    Tool '%s' has been called %d times with the same arguments and is not producing useful results.

                    You MUST either:
                    1. Try a DIFFERENT tool or approach
                    2. Answer with the information already gathered
                    3. Acknowledge that you cannot complete this part of the task

                    Do NOT call '%s' again with the same arguments.
                    Tools available: %s
                    Original task: %s
                    """, toolName, threshold, toolName, toolSummary, userInput);
        }

        return String.format("""
                [System Reflection]
                Tool '%s' has failed %d consecutive times with different arguments.

                Step back and reconsider:
                1. Is '%s' the right tool for this task?
                2. Could a different tool work better?
                3. Is this task actually solvable with the available tools?
                4. Should you answer with what you already know?

                Tools available: %s
                Original task: %s
                """, toolName, threshold, toolName, toolSummary, userInput);
    }

    private String buildHardLimitPrompt(String toolName, int failureCount, String userInput) {
        return String.format("""
                [System Tool Failure Limit]
                Tool '%s' has failed %d consecutive times. The fifth failure already triggered
                reflection, and the permitted final retry also failed.

                Do not call any more tools. Produce the best final answer possible from the
                information already gathered, and clearly state what could not be completed.
                Original task: %s
                """, toolName, failureCount, userInput);
    }

    private String summarizeToolsUsed(List<ReActStep> allSteps) {
        if (allSteps == null || allSteps.isEmpty()) return "(none)";
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (ReActStep step : allSteps) {
            if (step.toolCalls() != null) {
                for (ToolCall tc : step.toolCalls()) {
                    seen.add(tc.toolName());
                }
            }
        }
        return String.join(", ", seen);
    }

    /** Reset state (for testing or new runs). */
    public void reset() {
        toolFailureCounts.clear();
    }

    /**
     * Signal returned when reflection or the post-reflection hard limit is reached.
     *
     * @param prompt the reflection prompt to inject
     * @param hardLimit whether the Tool loop must terminate immediately
     */
    public record ReflectionSignal(String prompt, boolean hardLimit) {}
}

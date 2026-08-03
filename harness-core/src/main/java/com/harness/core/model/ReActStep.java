package com.harness.core.model;

import java.util.Collections;
import java.util.List;

/**
 * One iteration in the ReAct loop: Thought -> Action -> Observation.
 */
public record ReActStep(
        int stepNumber,
        String thought,
        String action,
        List<ToolCall> toolCalls,
        List<ToolResult> toolResults,
        String observation,
        InspectionResult inspection
) {

    // ── ThreadLocal: 由 ReActEngine 在每轮工具执行前设置，供 Tool 读取执行历史 ──

    private static final ThreadLocal<List<ReActStep>> CURRENT_STEPS = new ThreadLocal<>();

    /**
     * ReActEngine 在执行工具调用前设置当前轮次的历史步骤。
     */
    public static void setCurrentSteps(List<ReActStep> steps) {
        CURRENT_STEPS.set(steps);
    }

    /**
     * ReActEngine 在工具调用完成后清理。
     */
    public static void clearCurrentSteps() {
        CURRENT_STEPS.remove();
    }

    /**
     * 获取当前 ReAct 循环中已执行的所有步骤（只读）。
     * 工具内部可据此判断调用历史，不经过模型参数。
     */
    public static List<ReActStep> getCurrentSteps() {
        List<ReActStep> steps = CURRENT_STEPS.get();
        return steps != null ? steps : Collections.emptyList();
    }

    /**
     * 统计指定工具在当前 ReAct 循环中已被调用的次数。
     * 工具内部可据此判断是否为重试，无需模型传参。
     */
    public static int getInvocationCount(String toolName) {
        List<ReActStep> steps = getCurrentSteps();
        int count = 0;
        for (ReActStep step : steps) {
            if (step.toolCalls() != null) {
                for (ToolCall tc : step.toolCalls()) {
                    if (toolName.equals(tc.toolName())) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /**
     * 获取指定工具最近一次调用声明的结构化结果状态。
     */
    public static ToolResult.ResultStatus getLastToolResultStatus(String toolName) {
        List<ReActStep> steps = getCurrentSteps();
        for (int i = steps.size() - 1; i >= 0; i--) {
            ReActStep step = steps.get(i);
            if (step.toolResults() == null) {
                continue;
            }
            for (int j = step.toolResults().size() - 1; j >= 0; j--) {
                ToolResult result = step.toolResults().get(j);
                if (toolName.equals(result.toolName())) {
                    return result.status();
                }
            }
        }
        return null;
    }

    /**
     * 判断指定工具在当前 ReAct 运行中是否曾声明过目标结果状态。
     */
    public static boolean hasToolResultStatus(String toolName, ToolResult.ResultStatus status) {
        if (status == null) {
            return false;
        }
        for (ReActStep step : getCurrentSteps()) {
            if (step.toolResults() == null) {
                continue;
            }
            for (ToolResult result : step.toolResults()) {
                if (toolName.equals(result.toolName()) && status == result.status()) {
                    return true;
                }
            }
        }
        return false;
    }

    // ── Record 本体 ──

    public record InspectionResult(
            InspectionStatus status,
            String reason
    ) {
        public enum InspectionStatus {
            PASS,           // Tool executed correctly, result is usable
            TOOL_ERROR,     // Tool execution failed
            WRONG_TOOL,     // Wrong tool was selected
            INSUFFICIENT,   // Result doesn't fully answer the question
            CONFIRMATION_REQUIRED, // Tool was blocked pending explicit confirmation
            CONFIRMATION_REJECTED, // User rejected the pending tool execution
            CONFIRMATION_EXPIRED,  // User did not decide before expiry
            LOOP_DETECTED   // Repeated tool calls detected — force stop
        }
    }
}

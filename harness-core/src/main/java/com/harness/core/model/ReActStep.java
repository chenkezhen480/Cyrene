package com.harness.core.model;

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
    public record InspectionResult(
            InspectionStatus status,
            String reason
    ) {
        public enum InspectionStatus {
            PASS,           // Tool executed correctly, result is usable
            TOOL_ERROR,     // Tool execution failed
            WRONG_TOOL,     // Wrong tool was selected
            INSUFFICIENT,   // Result doesn't fully answer the question
            NEEDS_RETRY,    // Should retry with different params
            LOOP_DETECTED   // Repeated tool calls detected — force stop
        }
    }
}

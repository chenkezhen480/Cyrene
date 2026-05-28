package com.harness.core.model;

/**
 * Result returned from a tool execution.
 */
public record ToolResult(
        String toolCallId,
        String toolName,
        boolean success,
        String output,
        String error,
        long durationMs
) {
    public static ToolResult ok(String toolCallId, String toolName, String output, long durationMs) {
        return new ToolResult(toolCallId, toolName, true, output, null, durationMs);
    }

    public static ToolResult fail(String toolCallId, String toolName, String error, long durationMs) {
        return new ToolResult(toolCallId, toolName, false, null, error, durationMs);
    }
}

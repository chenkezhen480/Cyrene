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
        long durationMs,
        ResultStatus status,
        ToolOutput content
) {

    public ToolResult {
        if (success && content == null) {
            content = ToolOutput.text(output);
        }
    }

    /** Source-compatible constructor for callers that do not yet provide typed content. */
    public ToolResult(
            String toolCallId,
            String toolName,
            boolean success,
            String output,
            String error,
            long durationMs,
            ResultStatus status
    ) {
        this(toolCallId, toolName, success, output, error, durationMs, status,
                success ? ToolOutput.text(output) : null);
    }

    /**
     * Structured result status that tools can explicitly declare.
     * When present, Inspector trusts it directly instead of guessing from output text.
     * null means "no explicit status" — Inspector falls back to heuristic detection.
     */
    public enum ResultStatus {
        /** Tool found and returned useful results. */
        SUCCESS,
        /** Tool found zero results (empty retrieval, no matches, etc.). */
        EMPTY,
        /** Tool found results but they are irrelevant to the query (e.g. low rerank scores). */
        LOW_RELEVANCE,
        /** Tool found a near-miss result and made one implicit strategy escalation eligible.
         *  Reflector should NOT count this as failure — the tool is still actively trying. */
        ESCALATING,
        /** Tool execution was blocked until an explicit confirmation policy allows it. */
        CONFIRMATION_REQUIRED,
        /** User explicitly rejected the pending tool execution. */
        CONFIRMATION_REJECTED,
        /** The pending confirmation expired before the user decided. */
        CONFIRMATION_EXPIRED,
        /** The enclosing request was cancelled while waiting for confirmation. */
        CONFIRMATION_CANCELLED
    }

    // --- ThreadLocal for tools to communicate status without changing Tool.execute() signature ---

    private static final ThreadLocal<ResultStatus> CURRENT_STATUS = new ThreadLocal<>();

    /**
     * Set the result status for the current tool execution.
     * Called by tools (e.g. KnowledgeBaseTool) before returning from execute().
     */
    public static void setCurrentStatus(ResultStatus status) {
        CURRENT_STATUS.set(status);
    }

    /**
     * Consume the result status set by the tool and clear the ThreadLocal.
     * Called by ToolExecutor after tool.execute() returns.
     */
    public static ResultStatus consumeCurrentStatus() {
        ResultStatus s = CURRENT_STATUS.get();
        CURRENT_STATUS.remove();
        return s;
    }

    /**
     * Clear any status left by a failed tool execution.
     */
    public static void clearCurrentStatus() {
        CURRENT_STATUS.remove();
    }

    // --- Factory methods ---

    public static ToolResult ok(String toolCallId, String toolName, String output, long durationMs) {
        return ok(toolCallId, toolName, ToolOutput.text(output), durationMs, null);
    }

    public static ToolResult ok(String toolCallId, String toolName, String output, long durationMs, ResultStatus status) {
        return ok(toolCallId, toolName, ToolOutput.text(output), durationMs, status);
    }

    public static ToolResult ok(
            String toolCallId,
            String toolName,
            ToolOutput content,
            long durationMs,
            ResultStatus status
    ) {
        ToolOutput normalized = content == null ? ToolOutput.empty() : content;
        return new ToolResult(
                toolCallId,
                toolName,
                true,
                normalized.modelContent(),
                null,
                durationMs,
                status,
                normalized);
    }

    public static ToolResult fail(String toolCallId, String toolName, String error, long durationMs) {
        return new ToolResult(toolCallId, toolName, false, null, error, durationMs, null, null);
    }

    public static ToolResult confirmationRequired(String toolCallId, String toolName, String message) {
        return new ToolResult(toolCallId, toolName, false, null, message, 0,
                ResultStatus.CONFIRMATION_REQUIRED, null);
    }

    public static ToolResult confirmationRejected(String toolCallId, String toolName, String message) {
        return new ToolResult(toolCallId, toolName, false, null, message, 0,
                ResultStatus.CONFIRMATION_REJECTED, null);
    }

    public static ToolResult confirmationExpired(String toolCallId, String toolName, String message) {
        return new ToolResult(toolCallId, toolName, false, null, message, 0,
                ResultStatus.CONFIRMATION_EXPIRED, null);
    }

    public static ToolResult confirmationCancelled(String toolCallId, String toolName, String message) {
        return new ToolResult(toolCallId, toolName, false, null, message, 0,
                ResultStatus.CONFIRMATION_CANCELLED, null);
    }
}

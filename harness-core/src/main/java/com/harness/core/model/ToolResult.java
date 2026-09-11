package com.harness.core.model;

import com.fasterxml.jackson.annotation.JsonAlias;

/** Persisted result of one Tool invocation. */
public record ToolResult(
        String toolCallId,
        String toolName,
        boolean success,
        String output,
        String error,
        long durationMs,
        @JsonAlias("status") ResultStatus resultStatus,
        ToolOutput content,
        ExecutionStatus executionStatus,
        String validationSource,
        String validationReason
) {
    private static final int MAX_VALIDATION_SOURCE_LENGTH = 128;
    private static final int MAX_VALIDATION_REASON_LENGTH = 512;

    public ToolResult {
        executionStatus = executionStatus == null
                ? legacyExecutionStatus(success, resultStatus)
                : executionStatus;
        success = executionStatus == ExecutionStatus.SUCCEEDED;
        if (success && content == null) {
            content = ToolOutput.text(output);
        }
        if (success && resultStatus == null) {
            resultStatus = ResultStatus.AVAILABLE;
        }
        validationSource = bounded(
                validationSource, "validationSource", MAX_VALIDATION_SOURCE_LENGTH);
        validationReason = bounded(
                validationReason, "validationReason", MAX_VALIDATION_REASON_LENGTH);
        if (resultStatus == ResultStatus.VERIFIED
                && (validationSource == null || validationReason == null)) {
            throw new IllegalArgumentException(
                    "VERIFIED results require validationSource and validationReason");
        }
        if (resultStatus != ResultStatus.VERIFIED
                && (validationSource != null || validationReason != null)) {
            throw new IllegalArgumentException(
                    "validation evidence is only allowed for VERIFIED results");
        }
    }

    /** Source-compatible constructor for typed content without execution metadata. */
    public ToolResult(
            String toolCallId,
            String toolName,
            boolean success,
            String output,
            String error,
            long durationMs,
            ResultStatus resultStatus,
            ToolOutput content
    ) {
        this(toolCallId, toolName, success, output, error, durationMs, resultStatus, content,
                null, null, null);
    }

    /** Source-compatible constructor for text-only callers. */
    public ToolResult(
            String toolCallId,
            String toolName,
            boolean success,
            String output,
            String error,
            long durationMs,
            ResultStatus resultStatus
    ) {
        this(toolCallId, toolName, success, output, error, durationMs, resultStatus,
                success ? ToolOutput.text(output) : null);
    }

    public static ToolResult fromOutcome(
            String toolCallId,
            String toolName,
            ToolExecutionOutcome outcome,
            long durationMs
    ) {
        ToolOutput content = outcome.content();
        return new ToolResult(
                toolCallId,
                toolName,
                outcome.executionStatus() == ExecutionStatus.SUCCEEDED,
                content == null ? null : content.modelContent(),
                outcome.error(),
                durationMs,
                outcome.resultStatus(),
                content,
                outcome.executionStatus(),
                outcome.validationSource(),
                outcome.validationReason());
    }

    public static ToolResult ok(String toolCallId, String toolName, String output, long durationMs) {
        return ok(toolCallId, toolName, ToolOutput.text(output), durationMs, ResultStatus.AVAILABLE);
    }

    public static ToolResult ok(
            String toolCallId,
            String toolName,
            String output,
            long durationMs,
            ResultStatus resultStatus
    ) {
        return ok(toolCallId, toolName, ToolOutput.text(output), durationMs, resultStatus);
    }

    public static ToolResult ok(
            String toolCallId,
            String toolName,
            ToolOutput content,
            long durationMs,
            ResultStatus resultStatus
    ) {
        return fromOutcome(
                toolCallId,
                toolName,
                ToolExecutionOutcome.succeeded(content, resultStatus),
                durationMs);
    }

    public static ToolResult verified(
            String toolCallId,
            String toolName,
            ToolOutput content,
            long durationMs,
            String validationSource,
            String validationReason
    ) {
        return fromOutcome(
                toolCallId,
                toolName,
                ToolExecutionOutcome.verified(content, validationSource, validationReason),
                durationMs);
    }

    public static ToolResult fail(String toolCallId, String toolName, String error, long durationMs) {
        return fromOutcome(
                toolCallId, toolName, ToolExecutionOutcome.failed(error), durationMs);
    }

    public static ToolResult confirmationRequired(String toolCallId, String toolName, String message) {
        return unsuccessful(
                toolCallId, toolName, ExecutionStatus.CONFIRMATION_REQUIRED,
                ResultStatus.PENDING, message);
    }

    public static ToolResult confirmationRejected(String toolCallId, String toolName, String message) {
        return unsuccessful(
                toolCallId, toolName, ExecutionStatus.REJECTED,
                ResultStatus.CONTRACT_FAILED, message);
    }

    public static ToolResult confirmationExpired(String toolCallId, String toolName, String message) {
        return unsuccessful(
                toolCallId, toolName, ExecutionStatus.EXPIRED,
                ResultStatus.CONTRACT_FAILED, message);
    }

    public static ToolResult confirmationCancelled(String toolCallId, String toolName, String message) {
        return unsuccessful(
                toolCallId, toolName, ExecutionStatus.CANCELLED,
                ResultStatus.CONTRACT_FAILED, message);
    }

    private static ToolResult unsuccessful(
            String toolCallId,
            String toolName,
            ExecutionStatus executionStatus,
            ResultStatus resultStatus,
            String message
    ) {
        return new ToolResult(
                toolCallId, toolName, false, null, message, 0, resultStatus, null,
                executionStatus, null, null);
    }

    private static ExecutionStatus legacyExecutionStatus(
            boolean success,
            ResultStatus resultStatus
    ) {
        if (success) {
            return ExecutionStatus.SUCCEEDED;
        }
        if (resultStatus == ResultStatus.PENDING) {
            return ExecutionStatus.CONFIRMATION_REQUIRED;
        }
        return ExecutionStatus.FAILED;
    }

    private static String bounded(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }
}

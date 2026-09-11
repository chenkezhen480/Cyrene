package com.harness.core.model;

/** Explicit result channel returned by a Tool; no ThreadLocal state is involved. */
public record ToolExecutionOutcome(
        ExecutionStatus executionStatus,
        ResultStatus resultStatus,
        ToolOutput content,
        String error,
        String validationSource,
        String validationReason
) {
    private static final int MAX_VALIDATION_SOURCE_LENGTH = 128;
    private static final int MAX_VALIDATION_REASON_LENGTH = 512;

    public ToolExecutionOutcome {
        if (executionStatus == null) {
            throw new IllegalArgumentException("executionStatus is required");
        }
        if (executionStatus == ExecutionStatus.SUCCEEDED) {
            content = content == null ? ToolOutput.empty() : content;
            resultStatus = resultStatus == null ? ResultStatus.AVAILABLE : resultStatus;
            error = null;
        } else if (error == null || error.isBlank()) {
            throw new IllegalArgumentException("error is required for unsuccessful execution");
        }
        validationSource = bounded(validationSource, "validationSource",
                MAX_VALIDATION_SOURCE_LENGTH);
        validationReason = bounded(validationReason, "validationReason",
                MAX_VALIDATION_REASON_LENGTH);
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

    public static ToolExecutionOutcome available(ToolOutput content) {
        return succeeded(content, ResultStatus.AVAILABLE);
    }

    public static ToolExecutionOutcome succeeded(ToolOutput content, ResultStatus resultStatus) {
        return new ToolExecutionOutcome(
                ExecutionStatus.SUCCEEDED, resultStatus, content, null, null, null);
    }

    public static ToolExecutionOutcome verified(
            ToolOutput content,
            String validationSource,
            String validationReason
    ) {
        return new ToolExecutionOutcome(
                ExecutionStatus.SUCCEEDED,
                ResultStatus.VERIFIED,
                content,
                null,
                validationSource,
                validationReason);
    }

    public static ToolExecutionOutcome failed(String error) {
        return new ToolExecutionOutcome(
                ExecutionStatus.FAILED, null, null, error, null, null);
    }

    private static String bounded(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }
}

package com.harness.core.exception;

import java.util.Map;

/** Explicit failure raised while producing or validating structured output. */
public final class StructuredOutputException extends AgentException {

    public enum Code {
        STRUCTURED_OUTPUT_UNSUPPORTED,
        STRUCTURED_OUTPUT_REFUSED,
        STRUCTURED_OUTPUT_TRUNCATED,
        STRUCTURED_OUTPUT_EMPTY,
        STRUCTURED_OUTPUT_INVALID_JSON,
        STRUCTURED_OUTPUT_SCHEMA_INVALID,
        STRUCTURED_OUTPUT_SCHEMA_MISMATCH
    }

    private final Code code;
    private final Map<String, Object> details;

    public StructuredOutputException(Code code, String message) {
        this(code, message, Map.of(), null);
    }

    public StructuredOutputException(
            Code code, String message, Map<String, Object> details) {
        this(code, message, details, null);
    }

    public StructuredOutputException(
            Code code, String message, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = details != null ? Map.copyOf(details) : Map.of();
    }

    public Code code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}

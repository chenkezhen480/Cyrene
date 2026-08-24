package com.harness.server.api;

public enum ApiErrorCode {
    INVALID_REQUEST,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    GRAPH_PARSE_FAILED,
    GRAPH_OPERATION_FAILED,
    STRUCTURED_OUTPUT_UNSUPPORTED,
    STRUCTURED_OUTPUT_REFUSED,
    STRUCTURED_OUTPUT_TRUNCATED,
    STRUCTURED_OUTPUT_EMPTY,
    STRUCTURED_OUTPUT_INVALID_JSON,
    STRUCTURED_OUTPUT_SCHEMA_INVALID,
    STRUCTURED_OUTPUT_SCHEMA_MISMATCH,
    INTERNAL_ERROR;

    public static ApiErrorCode fromHttpStatus(int status) {
        return switch (status) {
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 409 -> CONFLICT;
            default -> status >= 500 ? INTERNAL_ERROR : INVALID_REQUEST;
        };
    }
}

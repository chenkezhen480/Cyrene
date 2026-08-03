package com.harness.server.api;

public enum ApiErrorCode {
    INVALID_REQUEST,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    GRAPH_PARSE_FAILED,
    GRAPH_OPERATION_FAILED,
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

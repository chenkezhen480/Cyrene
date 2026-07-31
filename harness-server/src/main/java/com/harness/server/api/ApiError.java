package com.harness.server.api;

import java.util.Map;

public record ApiError(
        ApiErrorCode code,
        String message,
        Map<String, Object> details
) {
    public ApiError {
        if (code == null) {
            throw new IllegalArgumentException("code is required");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static ApiError of(ApiErrorCode code, String message) {
        return new ApiError(code, message, Map.of());
    }
}

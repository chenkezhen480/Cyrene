package com.harness.server.api;

import io.javalin.http.Context;

import java.util.Map;

public final class ApiResponses {

    private ApiResponses() {
    }

    public static void error(
            Context context,
            int status,
            ApiErrorCode code,
            String message
    ) {
        error(context, status, code, message, Map.of());
    }

    public static void error(
            Context context,
            int status,
            ApiErrorCode code,
            String message,
            Map<String, Object> details
    ) {
        context.status(status).json(new ApiError(code, safeMessage(message), details));
    }

    private static String safeMessage(String message) {
        return message == null || message.isBlank() ? "Request failed" : message;
    }
}

package com.harness.server;

import io.javalin.http.Context;

final class ApiRequestParameters {

    private ApiRequestParameters() {
    }

    static int limit(Context context, int defaultLimit, int maxLimit) {
        String value = context.queryParam("limit");
        if (value == null || value.isBlank()) {
            return defaultLimit;
        }
        try {
            int limit = Integer.parseInt(value);
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be greater than 0");
            }
            return Math.min(limit, maxLimit);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("limit must be an integer", e);
        }
    }

    static String requiredQuery(Context context, String name) {
        String value = context.queryParam(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    static String optionalQuery(Context context, String name) {
        String value = context.queryParam(name);
        return value == null ? "" : value;
    }
}

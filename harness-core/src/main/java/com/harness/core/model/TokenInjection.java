package com.harness.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Describes how a credential token is injected into an HTTP request.
 *
 * @param location injection point: {@code "header"}, {@code "query"}, or {@code "cookie"}
 * @param name     the header/query-param/cookie name (e.g. {@code "Authorization"})
 * @param prefix   optional prefix prepended to the token value (e.g. {@code "Bearer "})
 */
public record TokenInjection(
        @JsonProperty(defaultValue = "header") String location,
        String name,
        String prefix
) {
    public TokenInjection {
        if (location == null || location.isBlank()) location = "header";
        if (prefix == null) prefix = "";
    }
}

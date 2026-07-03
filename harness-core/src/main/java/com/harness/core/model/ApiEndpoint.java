package com.harness.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A single discovered API endpoint declaration (§4.1 of TODO5).
 * Maps to one entry in the {@code endpoints} array of {@code project-apis.json}.
 */
public record ApiEndpoint(
        String id,
        String name,
        String description,
        String method,
        String path,
        String baseUrl,
        /** {@code "openapi"} or {@code "code_scan"} */
        String source,
        AuthMode authMode,
        String credentialKey,
        TokenInjection tokenInjection,
        /** JSON Schema describing the expected parameters */
        JsonNode parameters,
        /** Whether this endpoint has been confirmed by a human to be active */
        @JsonProperty(defaultValue = "false") boolean confirmed,
        /** Whether the user has acknowledged risk for high-risk combos (non-GET + BOT) */
        @JsonProperty(defaultValue = "false") boolean riskAcknowledged
) {
    public ApiEndpoint {
        if (id == null || id.isBlank()) id = "";
        if (method == null) method = "GET";
        if (confirmed == false) confirmed = false; // default
        if (riskAcknowledged == false) riskAcknowledged = false;
    }

    /**
     * Returns true if this endpoint is high-risk: non-GET with BOT auth.
     * Such combos require explicit {@code riskAcknowledged = true}.
     */
    @JsonIgnore
    public boolean isHighRisk() {
        return authMode == AuthMode.BOT && !"GET".equalsIgnoreCase(method);
    }
}

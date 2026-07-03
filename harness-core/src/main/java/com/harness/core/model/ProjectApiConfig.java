package com.harness.core.model;

import java.util.List;

/**
 * Root structure of {@code project-apis.json} (§4.1 of TODO5).
 *
 * @param discoveredAt ISO-8601 timestamp of when the scan was performed
 * @param sourceRoot   absolute path to the scanned project root
 * @param endpoints    list of discovered API endpoint declarations
 */
public record ProjectApiConfig(
        String discoveredAt,
        String sourceRoot,
        List<ApiEndpoint> endpoints
) {
    public ProjectApiConfig {
        if (endpoints == null) endpoints = List.of();
    }
}

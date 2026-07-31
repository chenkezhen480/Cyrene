package com.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Allows a tool to require confirmation only for specific argument combinations.
 */
public interface ArgumentAwareConfirmationTool extends Tool {

    boolean requiresConfirmation(JsonNode arguments);

    default String confirmationSummary(JsonNode arguments) {
        return spec().description();
    }
}

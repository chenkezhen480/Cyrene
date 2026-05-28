package com.harness.core.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A request to invoke a tool during ReAct execution.
 */
public record ToolCall(
        String id,
        String toolName,
        JsonNode arguments
) {
    public static ToolCall of(String toolName, JsonNode arguments) {
        return new ToolCall(java.util.UUID.randomUUID().toString(), toolName, arguments);
    }
}

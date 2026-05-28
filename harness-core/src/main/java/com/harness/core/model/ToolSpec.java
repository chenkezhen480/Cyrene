package com.harness.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/**
 * Specification of a tool that can be registered with the agent.
 */
public record ToolSpec(
        String name,
        String description,
        JsonNode parameters,
        Set<String> tags,
        boolean requiresConfirmation
) {
    public ToolSpec(String name, String description, JsonNode parameters) {
        this(name, description, parameters, Set.of(), false);
    }
}

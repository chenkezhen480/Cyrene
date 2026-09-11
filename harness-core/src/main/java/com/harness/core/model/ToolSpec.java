package com.harness.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
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
    public ToolSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ToolSpec name is required");
        }
        Set<String> normalized = new HashSet<>(tags == null ? Set.of() : tags);
        ToolCapability capability = ToolCapability.fromTags(normalized);
        if (normalized.stream().noneMatch(tag -> tag.startsWith("capability:"))) {
            normalized.add(capability.tag());
        }
        tags = Set.copyOf(normalized);
    }

    public ToolSpec(String name, String description, JsonNode parameters) {
        this(name, description, parameters, Set.of(), false);
    }

    public ToolSpec(
            String name,
            String description,
            JsonNode parameters,
            ToolCapability capability
    ) {
        this(name, description, parameters, Set.of(capability.tag()), false);
    }

    public ToolCapability capability() {
        return ToolCapability.fromTags(tags);
    }
}

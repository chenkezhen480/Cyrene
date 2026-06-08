package com.harness.core.model;

import java.util.List;
import java.util.Map;

/**
 * A skill is a set of operational instructions that tells the LLM how to perform
 * a specific task, including which tools to use and step-by-step procedures.
 */
public record Skill(
    String name,
    String description,
    String version,
    String systemPrompt,
    List<String> tools,
    Map<String, Object> parameters
) {
    public Skill {
        if (tools == null) tools = List.of();
        if (parameters == null) parameters = Map.of();
        if (version == null || version.isBlank()) version = "1.0.0";
    }
}

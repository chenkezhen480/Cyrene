package com.harness.input;

import com.harness.core.model.AgentMessage;
import com.harness.core.model.ParsedContent;

import java.util.List;

/** Unified input produced before context enrichment and ReAct execution. */
public record ProcessedInput(
        String userId,
        AgentMessage message,
        List<ParsedContent> parsedContents
) {
    public ProcessedInput {
        parsedContents = parsedContents != null ? List.copyOf(parsedContents) : List.of();
    }
}

package com.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Set;

/** Optional, verifiable completion conditions for one sub-agent task. */
public record SubAgentCompletionContract(
        Set<String> requiredSuccessfulTools,
        List<RequiredArtifact> requiredArtifacts,
        JsonNode outputSchema
) {
    public SubAgentCompletionContract {
        requiredSuccessfulTools = requiredSuccessfulTools == null
                ? Set.of()
                : Set.copyOf(requiredSuccessfulTools);
        requiredArtifacts = requiredArtifacts == null
                ? List.of()
                : List.copyOf(requiredArtifacts);
        outputSchema = outputSchema == null || outputSchema.isNull()
                ? null
                : outputSchema.deepCopy();
    }
}

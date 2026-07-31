package com.harness.tool.confirmation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.harness.core.model.RiskLevel;

import java.time.Instant;

/**
 * Immutable user-facing description of a pending tool execution.
 */
public record ConfirmationRequest(
        String requestId,
        String userId,
        String sessionId,
        String toolName,
        JsonNode arguments,
        String argumentsHash,
        String summary,
        RiskLevel riskLevel,
        Instant createdAt,
        Instant expiresAt
) {
    public ConfirmationRequest {
        arguments = arguments != null ? arguments.deepCopy() : NullNode.getInstance();
    }
}

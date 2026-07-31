package com.harness.tool.confirmation;

import com.fasterxml.jackson.databind.JsonNode;
import com.harness.core.model.CancellationToken;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Request-scoped callbacks and identity used while a tool waits for approval.
 */
public record ConfirmationExecutionContext(
        String userId,
        String sessionId,
        CancellationToken cancellationToken,
        Consumer<ConfirmationRequest> onConfirmationRequired,
        BiConsumer<ConfirmationRequest, ConfirmationDecision> onConfirmationResolved,
        BiConsumer<String, JsonNode> onExecutionStart
) {
    public ConfirmationExecutionContext {
        Objects.requireNonNull(onConfirmationRequired, "onConfirmationRequired");
        Objects.requireNonNull(onConfirmationResolved, "onConfirmationResolved");
        Objects.requireNonNull(onExecutionStart, "onExecutionStart");
    }
}

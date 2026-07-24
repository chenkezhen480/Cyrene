package com.harness.agent;

import com.harness.core.model.CancellationToken;

/**
 * Context for a single agent run, bound to a specific request.
 * Used to isolate sub-agent tasks per run.
 */
public record AgentRunContext(
        String runId,
        String sessionId,
        CancellationToken cancellationToken,
        String parentTraceId
) {
    public AgentRunContext {
        if (runId == null) throw new IllegalArgumentException("runId cannot be null");
        if (cancellationToken == null) throw new IllegalArgumentException("cancellationToken cannot be null");
    }

    /** Convenience constructor without parentTraceId (for backward compatibility). */
    public AgentRunContext(String runId, String sessionId, CancellationToken cancellationToken) {
        this(runId, sessionId, cancellationToken, null);
    }
}

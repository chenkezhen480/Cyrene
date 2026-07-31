package com.harness.agent;

import com.harness.core.model.CancellationToken;
import com.harness.tool.RunToolCatalog;

/**
 * Context for a single agent run, bound to a specific request.
 * Used to isolate sub-agent tasks per run.
 */
public record AgentRunContext(
        String runId,
        String sessionId,
        CancellationToken cancellationToken,
        String parentTraceId,
        RunToolCatalog toolCatalog
) {
    public AgentRunContext {
        if (runId == null) throw new IllegalArgumentException("runId cannot be null");
        if (cancellationToken == null) throw new IllegalArgumentException("cancellationToken cannot be null");
        if (toolCatalog == null) throw new IllegalArgumentException("toolCatalog cannot be null");
    }
}

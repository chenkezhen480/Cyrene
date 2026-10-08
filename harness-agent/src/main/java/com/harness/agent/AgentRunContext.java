package com.harness.agent;

import com.harness.core.model.CancellationToken;
import com.harness.core.model.AgentContext;
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
        RunToolCatalog toolCatalog,
        String turnId,
        Owner owner
) {
    /** Owner values come from prepared input, never sub-agent arguments or result text. */
    public record Owner(String userId, String tenantId, String identity) {
        public Owner {
            if (identity == null || identity.isBlank()) {
                throw new IllegalArgumentException("identity is required");
            }
        }

        public static Owner from(String userId, String tenantId, AgentContext context) {
            Object value = context.data().get(AgentContext.KEY_IDENTITY);
            String identity = value == null || value.toString().isBlank()
                    ? AgentContext.DEFAULT_IDENTITY : value.toString().trim();
            return new Owner(userId, tenantId, identity);
        }

        public AgentContext context() {
            var data = new java.util.HashMap<String, Object>();
            if (userId != null) data.put(AgentContext.KEY_USER_ID, userId);
            if (tenantId != null) data.put(AgentContext.KEY_TENANT_ID, tenantId);
            data.put(AgentContext.KEY_IDENTITY, identity);
            return AgentContext.of(java.util.Map.copyOf(data));
        }
    }

    public AgentRunContext(String runId, String sessionId, CancellationToken cancellationToken,
                           String parentTraceId, RunToolCatalog toolCatalog, String turnId) {
        this(runId, sessionId, cancellationToken, parentTraceId, toolCatalog, turnId, null);
    }

    public AgentRunContext(
            String runId,
            String sessionId,
            CancellationToken cancellationToken,
            String parentTraceId,
            RunToolCatalog toolCatalog
    ) {
        this(runId, sessionId, cancellationToken, parentTraceId,
                toolCatalog, parentTraceId != null ? parentTraceId : runId);
    }

    public AgentRunContext {
        if (runId == null) throw new IllegalArgumentException("runId cannot be null");
        if (cancellationToken == null) throw new IllegalArgumentException("cancellationToken cannot be null");
        if (toolCatalog == null) throw new IllegalArgumentException("toolCatalog cannot be null");
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalArgumentException("turnId cannot be blank");
        }
    }
}

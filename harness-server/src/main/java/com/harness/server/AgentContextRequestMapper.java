package com.harness.server;

import com.harness.core.model.AgentContext;

import java.util.HashMap;
import java.util.Map;

/** Removes server-owned context keys before mapping untrusted client context. */
public final class AgentContextRequestMapper {

    private AgentContextRequestMapper() {
    }

    public static Map<String, Object> sanitize(Map<String, Object> clientContext) {
        Map<String, Object> contextData = new HashMap<>(
                clientContext != null ? clientContext : Map.of());
        contextData.remove(AgentContext.KEY_GRAPH_REQUEST_CONTEXT);
        contextData.remove(AgentContext.KEY_KNOWLEDGE_REQUEST_CONTEXT);
        contextData.remove(AgentContext.KEY_NEEDS_GRAPH_KNOWLEDGE);
        return contextData;
    }
}

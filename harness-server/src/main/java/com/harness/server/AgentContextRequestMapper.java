package com.harness.server;

import com.harness.agent.voice.VoiceConversationService;
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
        // Consumed by the voice pipeline before the agent is built. The agent must never see
        // the recording reference: it would invite a second transcription of audio that has
        // already been transcribed into its own user message.
        contextData.remove(VoiceConversationService.CONTEXT_VOICE_INPUT);
        // Resolved from the tenant's stored profile, never from the request body: a caller
        // must not be able to lift its own tool restriction.
        contextData.remove(AgentContext.KEY_TOOL_DENYLIST);
        return contextData;
    }
}

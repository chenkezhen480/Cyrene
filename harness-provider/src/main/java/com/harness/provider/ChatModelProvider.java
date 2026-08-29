package com.harness.provider;

import com.harness.core.model.ModelUsage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 1. General Chat Model Provider
 * Handles: dialogue, tool calling, reasoning, ReAct loop.
 * Backed by LangChain4j ChatModel.
 *
 * <p>Thinking (reasoning) is controlled per-request via customParameters,
 * not via separate model instances.</p>
 */
public interface ChatModelProvider {

    ChatModel chatModel();

    default StreamingChatModel streamingModel() {
        return null;
    }

    String providerName();
    String modelName();

    default int timeoutSeconds() { return 300; }

    default Set<ModalCapability> modalCapabilities() {
        return ModalCapabilityRegistry.getCapabilities(modelName());
    }

    default ModelUsage modelUsage(ChatResponse response, long llmLatencyMs) {
        return ChatModelUsageMapper.map(response, llmLatencyMs);
    }

    /**
     * Builds provider-specific planning parameters without exposing protocol types to ReAct.
     * Providers with no portable thinking override still receive the ordinary tool catalog.
     */
    default ChatRequestParameters planningRequestParameters(
            Boolean enableThinking,
            List<ToolSpecification> toolSpecifications
    ) {
        Objects.requireNonNull(toolSpecifications, "toolSpecifications");
        if (toolSpecifications.isEmpty()) {
            return null;
        }
        return ChatRequestParameters.builder()
                .toolSpecifications(toolSpecifications)
                .build();
    }

    /**
     * Returns the model's context window size in tokens.
     * Providers may override this with the value captured from {@code model.conf}.
     */
    default int contextWindow() {
        return resolveContextWindow(modelName());
    }

    static int resolveContextWindow(String modelName) {
        if (modelName == null) return 128000;
        String m = modelName.toLowerCase();
        if (m.contains("claude")) return 200000;
        if (m.contains("gemini")) return 1000000;
        if (m.contains("deepseek")) return 65536;
        if (m.contains("qwen")) return 1048576;
        if (m.startsWith("o3") || m.startsWith("o4")) return 200000;
        if (m.startsWith("o1")) return 128000;
        if (m.contains("gpt-4")) return 128000;
        if (m.contains("gpt-3.5")) return 16385;
        return 128000;
    }
}

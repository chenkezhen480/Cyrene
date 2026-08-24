package com.harness.provider;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.model.ModelUsage;
import com.harness.core.model.FinalOutputContract;
import com.harness.core.exception.StructuredOutputException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.List;
import java.util.Objects;

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

    default boolean supportsStructuredOutput() {
        return false;
    }

    default ChatModel structuredChatModel() {
        throw new StructuredOutputException(
                StructuredOutputException.Code.STRUCTURED_OUTPUT_UNSUPPORTED,
                "Provider does not support strict structured output: " + providerName());
    }

    default ResponseFormat responseFormat(FinalOutputContract outputContract) {
        if (outputContract instanceof FinalOutputContract.Text) {
            return ResponseFormat.TEXT;
        }
        throw new StructuredOutputException(
                StructuredOutputException.Code.STRUCTURED_OUTPUT_UNSUPPORTED,
                "Provider does not support strict structured output: " + providerName());
    }

    /**
     * Returns the model's context window size in tokens.
     * Tries HARNESS_MODEL_CHAT_CONTEXT_WINDOW env var first; falls back to known model defaults.
     */
    default int contextWindow() {
        int envOverride = EnvConfig.get().getInt(EnvKey.MODEL_CHAT_CONTEXT_WINDOW, 0);
        if (envOverride > 0) return envOverride;
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

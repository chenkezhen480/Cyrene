package com.harness.ai.model;

import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

/**
 * 1. General Chat Model Provider
 * Handles: dialogue, tool calling, reasoning, ReAct loop.
 * Backed by LangChain4j ChatModel.
 */
public interface ChatModelProvider {

    ChatModel chatModel();

    /**
     * Returns a chat model with thinking/reasoning disabled.
     * Used for lightweight tasks like file summarization where thinking is wasteful.
     * Default: returns the same model as chatModel() (providers that support thinking should override).
     */
    default ChatModel chatModelNoThinking() {
        return chatModel();
    }

    default StreamingChatModel streamingModel() {
        return null;
    }

    String providerName();
    String modelName();

    /**
     * Returns the model's context window size in tokens.
     * Tries HARNESS_MODEL_CHAT_CONTEXT_WINDOW env var first; falls back to known model defaults.
     */
    default int contextWindow() {
        // Env var override takes highest priority
        int envOverride = EnvConfig.get().getInt(EnvKey.MODEL_CHAT_CONTEXT_WINDOW, 0);
        if (envOverride > 0) return envOverride;

        // Auto-detect from model name
        return resolveContextWindow(modelName());
    }

    static int resolveContextWindow(String modelName) {
        if (modelName == null) return 128000;
        String m = modelName.toLowerCase();

        // Anthropic
        if (m.contains("claude")) return 200000;

        // Google
        if (m.contains("gemini")) return 1000000;

        // DeepSeek
        if (m.contains("deepseek")) return 65536;

        // Qwen / DashScope
        if (m.contains("qwen")) return 131072;

        // OpenAI o3/o1 series
        if (m.startsWith("o3") || m.startsWith("o4")) return 200000;
        if (m.startsWith("o1")) return 128000;

        // GPT-4 variants
        if (m.contains("gpt-4")) return 128000;

        // GPT-3.5
        if (m.contains("gpt-3.5")) return 16385;

        // Default fallback
        return 128000;
    }
}

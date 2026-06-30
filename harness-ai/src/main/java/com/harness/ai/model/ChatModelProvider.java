package com.harness.ai.model;

import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

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
        if (m.contains("qwen")) return 131072;
        if (m.startsWith("o3") || m.startsWith("o4")) return 200000;
        if (m.startsWith("o1")) return 128000;
        if (m.contains("gpt-4")) return 128000;
        if (m.contains("gpt-3.5")) return 16385;
        return 128000;
    }
}

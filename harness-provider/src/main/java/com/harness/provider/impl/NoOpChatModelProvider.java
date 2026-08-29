package com.harness.provider.impl;

import com.harness.core.modelconfig.ModelConfigKey;
import com.harness.provider.ChatModelProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * Unconfigured Chat provider used during first-run administration.
 *
 * <p>Keeping an explicit provider object preserves normal dependency injection
 * and hot-reload wiring while preventing model calls before configuration.</p>
 */
public final class NoOpChatModelProvider implements ChatModelProvider {

    private final ChatModel chatModel = new ChatModel() {
        @Override
        public ChatResponse chat(ChatRequest request) {
            throw unavailable();
        }
    };

    @Override
    public ChatModel chatModel() {
        return chatModel;
    }

    @Override
    public String providerName() {
        return "none";
    }

    @Override
    public String modelName() {
        return "none";
    }

    private static UnsupportedOperationException unavailable() {
        return new UnsupportedOperationException(
                "Chat model not configured. Configure "
                        + ModelConfigKey.CHAT_PROVIDER
                        + " and its connection settings in model.conf or the Web console.");
    }
}

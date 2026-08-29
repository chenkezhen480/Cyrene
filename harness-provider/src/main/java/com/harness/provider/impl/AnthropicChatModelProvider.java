package com.harness.provider.impl;

import com.harness.provider.ChatModelProvider;
import com.harness.core.modelconfig.ModelConfig;
import com.harness.core.modelconfig.ModelConfigKey;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

import java.time.Duration;

public class AnthropicChatModelProvider implements ChatModelProvider {

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final int timeoutSeconds;

    public AnthropicChatModelProvider(ModelConfig cfg) {
        this.apiKey = cfg.requireString(ModelConfigKey.CHAT_API_KEY);
        this.baseUrl = cfg.getString(ModelConfigKey.CHAT_BASE_URL, "https://api.anthropic.com");
        this.model = cfg.getString(ModelConfigKey.CHAT_MODEL, "claude-sonnet-4-6");
        this.maxTokens = cfg.getInt(ModelConfigKey.CHAT_MAX_TOKENS, 12288);
        this.temperature = cfg.getDouble(ModelConfigKey.CHAT_TEMPERATURE, 0.7);
        this.timeoutSeconds = cfg.getInt(ModelConfigKey.CHAT_TIMEOUT_SECONDS, 300);
    }

    @Override
    public ChatModel chatModel() {
        return new RetryingChatModel(AnthropicChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .logRequests(true)
                .logResponses(true)
                .build());
    }

    @Override
    public StreamingChatModel streamingModel() {
        return null;
    }

    @Override
    public String providerName() { return "anthropic"; }

    @Override
    public String modelName() { return model; }

    @Override
    public int timeoutSeconds() { return timeoutSeconds; }
}

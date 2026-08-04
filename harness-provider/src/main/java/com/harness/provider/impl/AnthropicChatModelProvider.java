package com.harness.provider.impl;

import com.harness.provider.ChatModelProvider;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
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

    public AnthropicChatModelProvider() {
        EnvConfig cfg = EnvConfig.get();
        this.apiKey = cfg.requireString(EnvKey.MODEL_CHAT_API_KEY);
        this.baseUrl = cfg.getString(EnvKey.MODEL_CHAT_BASE_URL, "https://api.anthropic.com");
        this.model = cfg.getString(EnvKey.MODEL_CHAT_MODEL, "claude-sonnet-4-6");
        this.maxTokens = cfg.getInt(EnvKey.MODEL_CHAT_MAX_TOKENS, 12288);
        this.temperature = cfg.getDouble(EnvKey.MODEL_CHAT_TEMPERATURE, 0.7);
        this.timeoutSeconds = cfg.getInt(EnvKey.MODEL_CHAT_TIMEOUT_SECONDS, 300);
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
}

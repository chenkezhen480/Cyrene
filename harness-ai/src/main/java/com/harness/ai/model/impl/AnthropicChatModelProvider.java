package com.harness.ai.model.impl;

import com.harness.ai.model.ChatModelProvider;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

public class AnthropicChatModelProvider implements ChatModelProvider {

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxTokens;
    private final double temperature;

    public AnthropicChatModelProvider() {
        EnvConfig cfg = EnvConfig.get();
        this.apiKey = cfg.requireString(EnvKey.MODEL_CHAT_API_KEY);
        this.baseUrl = cfg.getString(EnvKey.MODEL_CHAT_BASE_URL, "https://api.anthropic.com");
        this.model = cfg.getString(EnvKey.MODEL_CHAT_MODEL, "claude-sonnet-4-6");
        this.maxTokens = cfg.getInt(EnvKey.MODEL_CHAT_MAX_TOKENS, 4096);
        this.temperature = cfg.getDouble(EnvKey.MODEL_CHAT_TEMPERATURE, 0.7);
    }

    @Override
    public ChatModel chatModel() {
        return AnthropicChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .logRequests(true)
                .logResponses(true)
                .build();
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

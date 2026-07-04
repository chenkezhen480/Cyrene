package com.harness.ai.model.impl;

import com.harness.ai.model.ChatModelProvider;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;

public class OpenAiChatModelProvider implements ChatModelProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiChatModelProvider.class);
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final boolean thinking;
    private final int timeoutSeconds;

    public OpenAiChatModelProvider() {
        EnvConfig cfg = EnvConfig.get();
        this.apiKey = cfg.requireString(EnvKey.MODEL_CHAT_API_KEY);
        this.baseUrl = cfg.getString(EnvKey.MODEL_CHAT_BASE_URL, "https://api.openai.com/v1");
        this.model = cfg.getString(EnvKey.MODEL_CHAT_MODEL, "gpt-4o");
        this.maxTokens = cfg.getInt(EnvKey.MODEL_CHAT_MAX_TOKENS, 12288);
        this.temperature = cfg.getDouble(EnvKey.MODEL_CHAT_TEMPERATURE, 0.7);
        this.thinking = cfg.getBool(EnvKey.MODEL_CHAT_THINKING, true);
        this.timeoutSeconds = cfg.getInt(EnvKey.MODEL_CHAT_TIMEOUT_MS, 300);
        log.info("[Model] OpenAI Chat initialized: model={}, baseUrl={}, maxTokens={}, temp={}, thinking={}, timeout={}s",
                model, baseUrl, maxTokens, temperature, thinking, timeoutSeconds);
    }

    @Override
    public ChatModel chatModel() {
        return new RetryingChatModel(OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .logRequests(true)
                .logResponses(true)
                .customParameters(Map.of("enable_thinking", thinking))
                .build());
    }

    @Override
    public StreamingChatModel streamingModel() {
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .customParameters(Map.of("enable_thinking", thinking))
                .build();
    }

    @Override
    public String providerName() { return "openai"; }

    @Override
    public String modelName() { return model; }
}

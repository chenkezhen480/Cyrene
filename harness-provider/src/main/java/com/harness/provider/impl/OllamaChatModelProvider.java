package com.harness.provider.impl;

import com.harness.provider.ChatModelProvider;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public class OllamaChatModelProvider implements ChatModelProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaChatModelProvider.class);
    private final String baseUrl;
    private final String model;
    private final double temperature;

    public OllamaChatModelProvider() {
        this(EnvConfig.get());
    }

    public OllamaChatModelProvider(EnvConfig cfg) {
        this.baseUrl = cfg.getString(EnvKey.MODEL_CHAT_BASE_URL, "http://localhost:11434");
        this.model = cfg.getString(EnvKey.MODEL_CHAT_MODEL, "llama3");
        this.temperature = cfg.getDouble(EnvKey.MODEL_CHAT_TEMPERATURE, 0.7);
        log.info("[Model] Ollama Chat initialized: model={}, baseUrl={}, temp={}", model, baseUrl, temperature);
    }

    @Override
    public ChatModel chatModel() {
        return new RetryingChatModel(OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(model)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(120))
                .build());
    }

    @Override
    public StreamingChatModel streamingModel() {
        return OllamaStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(model)
                .temperature(temperature)
                .build();
    }

    @Override
    public String providerName() { return "ollama"; }

    @Override
    public String modelName() { return model; }
}

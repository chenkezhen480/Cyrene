package com.harness.provider.impl;

import com.harness.provider.ChatModelProvider;
import com.harness.core.modelconfig.ModelConfig;
import com.harness.core.modelconfig.ModelConfigKey;
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
    private final int timeoutSeconds;

    public OllamaChatModelProvider(ModelConfig cfg) {
        this.baseUrl = cfg.getString(ModelConfigKey.CHAT_BASE_URL, "http://localhost:11434");
        this.model = cfg.getString(ModelConfigKey.CHAT_MODEL, "llama3");
        this.temperature = cfg.getDouble(ModelConfigKey.CHAT_TEMPERATURE, 0.7);
        this.timeoutSeconds = cfg.getInt(ModelConfigKey.CHAT_TIMEOUT_SECONDS, 120);
        log.info("[Model] Ollama Chat initialized: model={}, baseUrl={}, temp={}", model, baseUrl, temperature);
    }

    @Override
    public ChatModel chatModel() {
        return new RetryingChatModel(OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(model)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(timeoutSeconds))
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

    @Override
    public int timeoutSeconds() { return timeoutSeconds; }
}

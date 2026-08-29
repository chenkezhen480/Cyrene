package com.harness.provider.impl;

import com.harness.core.modelconfig.ModelConfig;
import com.harness.core.modelconfig.ModelConfigKey;
import com.harness.provider.SmallTaskModelProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;

/** OpenAI-compatible model for bounded, non-streaming internal small tasks. */
public final class OpenAiSmallTaskModelProvider implements SmallTaskModelProvider {
    private static final Logger log = LoggerFactory.getLogger(OpenAiSmallTaskModelProvider.class);
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxTokens;
    private final int timeoutSeconds;

    public OpenAiSmallTaskModelProvider(ModelConfig config) {
        this.apiKey = config.requireString(ModelConfigKey.SMALL_TASK_API_KEY);
        this.baseUrl = config.getString(ModelConfigKey.SMALL_TASK_BASE_URL, "https://api.openai.com/v1");
        this.model = config.getString(ModelConfigKey.SMALL_TASK_MODEL, "gpt-4o-mini");
        this.maxTokens = config.getInt(ModelConfigKey.SMALL_TASK_MAX_TOKENS, 50);
        this.timeoutSeconds = config.getInt(
                ModelConfigKey.SMALL_TASK_TIMEOUT_SECONDS, 30);
        log.info("[Model] Small-task model initialized: model={}, baseUrl={}, maxTokens={}",
                model, baseUrl, maxTokens);
    }

    @Override
    public ChatModel chatModel() {
        return OpenAiChatModel.builder().apiKey(apiKey).baseUrl(baseUrl).modelName(model)
                .maxTokens(maxTokens).temperature(0.0)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .logRequests(true).logResponses(true)
                .customParameters(Map.of("thinking", Map.of("type", "disabled"))).build();
    }
    @Override public String providerName() { return "openai"; }
    @Override public String modelName() { return model; }
}

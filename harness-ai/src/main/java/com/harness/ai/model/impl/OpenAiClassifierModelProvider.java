package com.harness.ai.model.impl;

import com.harness.ai.model.ClassifierModelProvider;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;

/**
 * OpenAI 兼容的分类器模型实现，用于 GapAnalyzer Tier 2。
 * 思考模式固定关闭，max_tokens 从 MODEL_CLASSIFIER_MAX_TOKENS 读取（默认 50）。
 */
public class OpenAiClassifierModelProvider implements ClassifierModelProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClassifierModelProvider.class);

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxTokens;

    public OpenAiClassifierModelProvider() {
        EnvConfig cfg = EnvConfig.get();
        this.apiKey = cfg.getString(EnvKey.MODEL_CLASSIFIER_API_KEY, "");
        this.baseUrl = cfg.getString(EnvKey.MODEL_CLASSIFIER_BASE_URL, "https://api.openai.com/v1");
        this.model = cfg.getString(EnvKey.MODEL_CLASSIFIER_MODEL, "gpt-4o-mini");
        this.maxTokens = cfg.getInt(EnvKey.MODEL_CLASSIFIER_MAX_TOKENS, 50);
        log.info("[Model] Classifier initialized: model={}, baseUrl={}, maxTokens={}", model, baseUrl, maxTokens);
    }

    @Override
    public ChatModel chatModel() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .maxTokens(maxTokens)
                .temperature(0.0)
                .timeout(Duration.ofSeconds(30))
                .logRequests(true)
                .logResponses(true)
                .customParameters(Map.of("thinking", Map.of("type", "disabled")))
                .build();
    }

    @Override
    public String providerName() { return "openai"; }

    @Override
    public String modelName() { return model; }
}

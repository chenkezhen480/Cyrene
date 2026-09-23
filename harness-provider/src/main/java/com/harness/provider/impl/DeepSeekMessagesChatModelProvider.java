package com.harness.provider.impl;

import com.harness.core.modelconfig.ModelConfig;
import com.harness.core.modelconfig.ModelConfigKey;
import com.harness.provider.ChatModelProvider;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

import java.time.Duration;

/**
 * DeepSeek official Anthropic Messages provider.
 *
 * <p>This path intentionally uses the Anthropic wire protocol instead of translating
 * DeepSeek through OpenAI Chat Completions. Tool calls therefore arrive as native
 * {@code tool_use} blocks and are normalized by LangChain4j before ReAct sees them.</p>
 */
public final class DeepSeekMessagesChatModelProvider implements ChatModelProvider {

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final int timeoutSeconds;

    public DeepSeekMessagesChatModelProvider(ModelConfig cfg) {
        this.apiKey = cfg.requireString(ModelConfigKey.CHAT_API_KEY);
        this.baseUrl = cfg.getString(
                ModelConfigKey.CHAT_BASE_URL,
                "https://api.deepseek.com/anthropic");
        this.model = cfg.getString(ModelConfigKey.CHAT_MODEL, "deepseek-flash");
        this.maxTokens = cfg.getInt(ModelConfigKey.CHAT_MAX_TOKENS, 12288);
        this.temperature = cfg.getDouble(ModelConfigKey.CHAT_TEMPERATURE, 0.7);
        this.timeoutSeconds = cfg.getInt(ModelConfigKey.CHAT_TIMEOUT_SECONDS, 300);
    }

    @Override
    public ChatModel chatModel() {
        return new RetryingChatModel(
                AnthropicChatModel.builder()
                        .httpClientBuilder(cancellableHttpClientBuilder())
                        .apiKey(apiKey)
                        .baseUrl(baseUrl)
                        .modelName(model)
                        .maxTokens(maxTokens)
                        .temperature(temperature)
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .returnThinking(true)
                        .sendThinking(true)
                        .logRequests(true)
                        .logResponses(true)
                        .build());
    }

    @Override
    public StreamingChatModel streamingModel() {
        return AnthropicStreamingChatModel.builder()
                .httpClientBuilder(cancellableHttpClientBuilder())
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .returnThinking(true)
                .sendThinking(true)
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    private HttpClientBuilder cancellableHttpClientBuilder() {
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        return new CancellableHttpClient.Builder()
                .connectTimeout(timeout)
                .readTimeout(timeout);
    }

    @Override
    public String providerName() {
        return "deepseek";
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public int timeoutSeconds() {
        return timeoutSeconds;
    }
}

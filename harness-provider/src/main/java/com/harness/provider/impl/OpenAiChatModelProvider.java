package com.harness.provider.impl;

import com.harness.provider.ChatModelProvider;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public class OpenAiChatModelProvider implements ChatModelProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiChatModelProvider.class);
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final OpenAiChatApiFormat apiFormat;
    private final int maxTokens;
    private final double temperature;
    private final boolean thinking;
    private final int timeoutSeconds;
    private final AtomicBoolean responsesThinkingWarningLogged = new AtomicBoolean();

    public OpenAiChatModelProvider() {
        this(OpenAiChatApiFormat.parse(EnvConfig.get().getString(
                EnvKey.MODEL_CHAT_API_FORMAT,
                OpenAiChatApiFormat.CHAT_COMPLETIONS.configValue())));
    }

    public OpenAiChatModelProvider(OpenAiChatApiFormat apiFormat) {
        EnvConfig cfg = EnvConfig.get();
        this.apiFormat = Objects.requireNonNull(apiFormat, "apiFormat");
        this.apiKey = cfg.requireString(EnvKey.MODEL_CHAT_API_KEY);
        this.baseUrl = cfg.getString(EnvKey.MODEL_CHAT_BASE_URL, "https://api.openai.com/v1");
        this.model = cfg.getString(EnvKey.MODEL_CHAT_MODEL, "gpt-4o");
        this.maxTokens = cfg.getInt(EnvKey.MODEL_CHAT_MAX_TOKENS, 12288);
        this.temperature = cfg.getDouble(EnvKey.MODEL_CHAT_TEMPERATURE, 0.7);
        this.thinking = cfg.getBool(EnvKey.MODEL_CHAT_THINKING, true);
        this.timeoutSeconds = cfg.getInt(EnvKey.MODEL_CHAT_TIMEOUT_SECONDS, 300);
        log.info("[Model] OpenAI Chat initialized: model={}, baseUrl={}, apiFormat={}, maxTokens={}, temp={}, thinking={}, timeout={}s",
                model, baseUrl, apiFormat.configValue(), maxTokens, temperature, thinking, timeoutSeconds);
        if (apiFormat == OpenAiChatApiFormat.RESPONSES && thinking) {
            log.warn("[Model] HARNESS_MODEL_CHAT_THINKING is not sent as enable_thinking with the Responses API; model defaults apply");
        }
    }

    @Override
    public ChatModel chatModel() {
        return new RetryingChatModel(createRawChatModel());
    }

    ChatModel createRawChatModel() {
        if (apiFormat == OpenAiChatApiFormat.RESPONSES) {
            return OpenAiResponsesChatModel.builder()
                    .httpClientBuilder(cancellableHttpClientBuilder())
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .modelName(model)
                    .maxOutputTokens(maxTokens)
                    .temperature(temperature)
                    .store(false)
                    .logRequests(true)
                    .logResponses(true)
                    .build();
        }
        return OpenAiChatModel.builder()
                .httpClientBuilder(cancellableHttpClientBuilder())
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .logRequests(true)
                .logResponses(true)
                .customParameters(Map.of("enable_thinking", thinking))
                .build();
    }

    @Override
    public StreamingChatModel streamingModel() {
        if (apiFormat == OpenAiChatApiFormat.RESPONSES) {
            return OpenAiResponsesStreamingChatModel.builder()
                    .httpClientBuilder(cancellableHttpClientBuilder())
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .modelName(model)
                    .maxOutputTokens(maxTokens)
                    .temperature(temperature)
                    .store(false)
                    .build();
        }
        return OpenAiStreamingChatModel.builder()
                .httpClientBuilder(cancellableHttpClientBuilder())
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
    public ChatRequestParameters planningRequestParameters(
            Boolean enableThinking,
            List<ToolSpecification> toolSpecifications
    ) {
        Objects.requireNonNull(toolSpecifications, "toolSpecifications");
        if (apiFormat == OpenAiChatApiFormat.RESPONSES) {
            if (enableThinking != null
                    && responsesThinkingWarningLogged.compareAndSet(false, true)) {
                log.warn("[Model] Request-level enableThinking is not sent with the Responses API; "
                        + "configure a Responses-compatible reasoning model explicitly if reasoning control is required");
            }
            if (toolSpecifications.isEmpty()) {
                return null;
            }
            return OpenAiResponsesChatRequestParameters.builder()
                    .toolSpecifications(toolSpecifications)
                    .store(false)
                    .build();
        }

        if (enableThinking == null && toolSpecifications.isEmpty()) {
            return null;
        }
        OpenAiChatRequestParameters.Builder builder =
                OpenAiChatRequestParameters.builder();
        if (enableThinking != null) {
            builder.customParameters(Map.of("enable_thinking", enableThinking));
        }
        if (!toolSpecifications.isEmpty()) {
            builder.toolSpecifications(toolSpecifications);
        }
        return builder.build();
    }

    private HttpClientBuilder cancellableHttpClientBuilder() {
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        return new CancellableHttpClient.Builder()
                .connectTimeout(timeout)
                .readTimeout(timeout);
    }

    @Override
    public String providerName() { return "openai"; }

    @Override
    public String modelName() { return model; }

}

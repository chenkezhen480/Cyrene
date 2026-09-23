package com.harness.provider.impl;

import com.harness.provider.ChatModelProvider;
import com.harness.core.model.ThinkingLevel;
import com.harness.core.modelconfig.ModelConfig;
import com.harness.core.modelconfig.ModelConfigKey;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public class OpenAiChatModelProvider implements ChatModelProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiChatModelProvider.class);

    /**
     * 思考参数方言。同一条 Chat Completions 线上，各后端认的参数名不同：
     * <ul>
     *   <li>{@code EFFORT} — 顶层 {@code reasoning_effort}（OpenAI / DeepSeek / Ollama
     *       兼容口 / Gemini 兼容口，vLLM 与 SGLang 会自动注入模板）</li>
     *   <li>{@code QWEN} — 顶层 {@code enable_thinking}，可选叠加 {@code thinking_budget}
     *       （DashScope 兼容模式的 Qwen 系模型，{@code reasoning_effort} 会被静默忽略）</li>
     * </ul>
     */
    private enum ThinkingDialect {
        EFFORT("effort"),
        QWEN("qwen");

        private final String configValue;

        ThinkingDialect(String configValue) {
            this.configValue = configValue;
        }

        static ThinkingDialect parse(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            for (ThinkingDialect dialect : values()) {
                if (dialect.configValue.equals(normalized)) {
                    return dialect;
                }
            }
            throw new IllegalStateException(
                    "Invalid " + ModelConfigKey.CHAT_THINKING_DIALECT + " '" + value
                            + "'. Allowed values: effort, qwen");
        }
    }

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final OpenAiChatApiFormat apiFormat;
    private final int maxTokens;
    private final double temperature;
    private final ThinkingDialect thinkingDialect;
    private final ThinkingLevel defaultThinkingLevel;
    private final ThinkingLevel maxThinkingLevel;
    private final int[] thinkingBudgets;
    private final String xhighValue;
    private final int timeoutSeconds;
    private final AtomicBoolean responsesThinkingWarningLogged = new AtomicBoolean();
    private final AtomicBoolean clampWarningLogged = new AtomicBoolean();

    public OpenAiChatModelProvider(ModelConfig cfg, OpenAiChatApiFormat apiFormat) {
        Objects.requireNonNull(cfg, "cfg");
        this.apiFormat = Objects.requireNonNull(apiFormat, "apiFormat");
        this.apiKey = cfg.requireString(ModelConfigKey.CHAT_API_KEY);
        this.baseUrl = cfg.getString(ModelConfigKey.CHAT_BASE_URL, "https://api.openai.com/v1");
        this.model = cfg.getString(ModelConfigKey.CHAT_MODEL, "gpt-4o");
        this.maxTokens = cfg.getInt(ModelConfigKey.CHAT_MAX_TOKENS, 12288);
        this.temperature = cfg.getDouble(ModelConfigKey.CHAT_TEMPERATURE, 0.7);
        this.thinkingDialect = resolveThinkingDialect(cfg);
        this.defaultThinkingLevel = resolveDefaultThinkingLevel(cfg);
        this.maxThinkingLevel = resolveMaxThinkingLevel(cfg);
        this.thinkingBudgets = resolveThinkingBudgets(cfg);
        this.xhighValue = normalizeXhighValue(cfg);
        this.timeoutSeconds = cfg.getInt(ModelConfigKey.CHAT_TIMEOUT_SECONDS, 300);
        log.info("[Model] OpenAI Chat initialized: model={}, baseUrl={}, apiFormat={}, maxTokens={}, temp={}, "
                        + "thinkingDialect={}, thinkingLevel={}, maxThinkingLevel={}, xhighValue={}, timeout={}s",
                model, baseUrl, apiFormat.configValue(), maxTokens, temperature,
                thinkingDialect.configValue, defaultThinkingLevel, maxThinkingLevel, xhighValue, timeoutSeconds);
        if (apiFormat == OpenAiChatApiFormat.RESPONSES && defaultThinkingLevel != null) {
            log.warn("[Model] chat.thinkingLevel is not sent with the Responses API; model defaults apply");
        }
    }

    /**
     * 方言解析：显式配置优先，否则按 provider 推导——DashScope 兼容模式只认
     * {@code enable_thinking}，其余 OpenAI 兼容后端一律 effort 方言。
     */
    private static ThinkingDialect resolveThinkingDialect(ModelConfig cfg) {
        String configured = cfg.getString(ModelConfigKey.CHAT_THINKING_DIALECT);
        if (configured != null && !configured.isBlank()) {
            return ThinkingDialect.parse(configured);
        }
        String provider = cfg.getString(ModelConfigKey.CHAT_PROVIDER, "openai")
                .trim().toLowerCase(Locale.ROOT);
        return "dashscope".equals(provider) ? ThinkingDialect.QWEN : ThinkingDialect.EFFORT;
    }

    /**
     * 模型级默认档位：{@code chat.thinkingLevel} 优先；否则迁移<strong>已废弃</strong>的
     * {@code chat.thinking}（true→MEDIUM / false→OFF）；两者皆无时——
     * QWEN 方言保持旧线级行为（enable_thinking=true，即 MEDIUM），
     * EFFORT 方言不发参数、交由模型默认（旧布尔参数在 effort 系后端本就被忽略）。
     */
    private static ThinkingLevel resolveDefaultThinkingLevel(ModelConfig cfg) {
        String level = cfg.getString(ModelConfigKey.CHAT_THINKING_LEVEL);
        if (level != null && !level.isBlank()) {
            return ThinkingLevel.parse(level.trim());
        }
        // 已废弃键：仅当 chat.thinkingLevel 未配置时才走到这里，供老配置平滑过渡。
        if (cfg.getString(ModelConfigKey.CHAT_THINKING) != null) {
            return cfg.getBool(ModelConfigKey.CHAT_THINKING, true)
                    ? ThinkingLevel.MEDIUM
                    : ThinkingLevel.OFF;
        }
        return ThinkingDialect.QWEN.equals(resolveThinkingDialect(cfg))
                ? ThinkingLevel.MEDIUM
                : null;
    }

    private static ThinkingLevel resolveMaxThinkingLevel(ModelConfig cfg) {
        String level = cfg.getString(ModelConfigKey.CHAT_THINKING_MAX_LEVEL);
        return level == null || level.isBlank() ? null : ThinkingLevel.parse(level.trim());
    }

    /** qwen 方言各档位（low/medium/high/xhigh）的 thinking_budget，未配置返回 null。 */
    private static int[] resolveThinkingBudgets(ModelConfig cfg) {
        List<String> parts = cfg.getCommaList(ModelConfigKey.CHAT_THINKING_BUDGETS);
        if (parts.isEmpty()) {
            return null;
        }
        if (parts.size() != 4) {
            throw new IllegalArgumentException(
                    ModelConfigKey.CHAT_THINKING_BUDGETS
                            + " must contain 4 integers for low,medium,high,xhigh");
        }
        int[] budgets = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                int budget = Integer.parseInt(parts.get(i));
                if (budget <= 0) {
                    throw new NumberFormatException("must be positive");
                }
                budgets[i] = budget;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        ModelConfigKey.CHAT_THINKING_BUDGETS
                                + " must contain 4 positive integers for low,medium,high,xhigh",
                        exception);
            }
        }
        return budgets;
    }

    /**
     * xhigh 档在 effort 方言下的下发值。Ollama 兼容口的值集是 none/low/medium/high/max，
     * 指向 Ollama 时应配置为 {@code max}；其余后端保持 OpenAI 词汇 {@code xhigh}。
     */
    private static String normalizeXhighValue(ModelConfig cfg) {
        String value = cfg.getString(ModelConfigKey.CHAT_THINKING_XHIGH_VALUE, "xhigh").trim();
        return value.isEmpty() ? "xhigh" : value;
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
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .httpClientBuilder(cancellableHttpClientBuilder())
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .maxRetries(0)
                .logRequests(true)
                .logResponses(true);
        if (defaultThinkingLevel != null) {
            applyThinking(builder, defaultThinkingLevel);
        }
        return builder.build();
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
        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                .httpClientBuilder(cancellableHttpClientBuilder())
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(timeoutSeconds));
        if (defaultThinkingLevel != null) {
            applyThinking(builder, defaultThinkingLevel);
        }
        return builder.build();
    }

    @Override
    public boolean requiresChatCompletionFinishReason() {
        return apiFormat == OpenAiChatApiFormat.CHAT_COMPLETIONS;
    }

    private void applyThinking(OpenAiChatModel.OpenAiChatModelBuilder builder, ThinkingLevel level) {
        if (thinkingDialect == ThinkingDialect.QWEN) {
            builder.customParameters(qwenThinkingParams(level));
        } else {
            builder.reasoningEffort(wireEffortValue(level));
        }
    }

    private void applyThinking(OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder, ThinkingLevel level) {
        if (thinkingDialect == ThinkingDialect.QWEN) {
            builder.customParameters(qwenThinkingParams(level));
        } else {
            builder.reasoningEffort(wireEffortValue(level));
        }
    }

    @Override
    public ChatRequestParameters planningRequestParameters(
            ThinkingLevel thinkingLevel,
            List<ToolSpecification> toolSpecifications
    ) {
        Objects.requireNonNull(toolSpecifications, "toolSpecifications");
        if (apiFormat == OpenAiChatApiFormat.RESPONSES) {
            if (thinkingLevel != null
                    && responsesThinkingWarningLogged.compareAndSet(false, true)) {
                log.warn("[Model] Request-level thinkingLevel is not sent with the Responses API; "
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

        ThinkingLevel effective = thinkingLevel != null ? clamped(thinkingLevel) : null;
        if (effective == null && toolSpecifications.isEmpty()) {
            return null;
        }
        OpenAiChatRequestParameters.Builder builder =
                OpenAiChatRequestParameters.builder();
        if (effective != null) {
            if (thinkingDialect == ThinkingDialect.QWEN) {
                builder.customParameters(qwenThinkingParams(effective));
            } else {
                builder.reasoningEffort(wireEffortValue(effective));
            }
        }
        if (!toolSpecifications.isEmpty()) {
            builder.toolSpecifications(toolSpecifications);
            builder.toolChoice(dev.langchain4j.model.chat.request.ToolChoice.AUTO);
        }
        return builder.build();
    }

    /** 超过 {@code chat.thinkingMaxLevel} 的档位降档发送，一次性告警（避免模型硬 400）。 */
    private ThinkingLevel clamped(ThinkingLevel level) {
        if (maxThinkingLevel != null && level.ordinal() > maxThinkingLevel.ordinal()) {
            if (clampWarningLogged.compareAndSet(false, true)) {
                log.warn("[Model] thinkingLevel {} exceeds {}={}, clamping to {}",
                        level, ModelConfigKey.CHAT_THINKING_MAX_LEVEL, maxThinkingLevel.configValue(),
                        maxThinkingLevel.configValue());
            }
            return maxThinkingLevel;
        }
        return level;
    }

    /** effort 方言线值：off→"none"（关思考），xhigh→可配置别名（Ollama 为 max），其余按档位词汇。 */
    private String wireEffortValue(ThinkingLevel level) {
        if (level == ThinkingLevel.OFF) {
            return "none";
        }
        return level == ThinkingLevel.XHIGH ? xhighValue : level.configValue();
    }

    /** qwen 方言参数：off → enable_thinking=false，其余档 enable_thinking=true，可选叠加预算。 */
    private Map<String, Object> qwenThinkingParams(ThinkingLevel level) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("enable_thinking", level != ThinkingLevel.OFF);
        Integer budget = qwenBudget(level);
        if (budget != null) {
            params.put("thinking_budget", budget);
        }
        return params;
    }

    private Integer qwenBudget(ThinkingLevel level) {
        if (thinkingBudgets == null || level == ThinkingLevel.OFF) {
            return null;
        }
        // LOW..XHIGH 依次对应 thinkingBudgets[0..3]
        return thinkingBudgets[level.ordinal() - 1];
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

    @Override
    public int timeoutSeconds() { return timeoutSeconds; }

}

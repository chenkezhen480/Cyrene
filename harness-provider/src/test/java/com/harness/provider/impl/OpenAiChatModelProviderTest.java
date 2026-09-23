package com.harness.provider.impl;

import com.harness.core.model.ThinkingLevel;
import com.harness.core.modelconfig.ModelConfig;
import com.harness.core.modelconfig.ModelConfigKey;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiChatModelProviderTest {

    @Test
    void responsesFormat_buildsResponsesModelsForBlockingStructuredAndStreamingCalls() {
        OpenAiChatModelProvider provider =
                new OpenAiChatModelProvider(config(), ChatApiFormat.RESPONSES);

        assertThat(provider.createRawChatModel())
                .isInstanceOf(OpenAiResponsesChatModel.class);
        assertThat(provider.streamingModel()).isNotNull();
    }

    @Test
    void chatCompletionsFormat_keepsExistingModels() {
        OpenAiChatModelProvider provider =
                new OpenAiChatModelProvider(config(), ChatApiFormat.CHAT_COMPLETIONS);

        assertThat(provider.createRawChatModel()).isInstanceOf(OpenAiChatModel.class);
        assertThat(provider.streamingModel()).isNotNull();
        assertThat(provider.planningRequestParameters(null, List.of(toolSpecification())))
                .isInstanceOf(OpenAiChatRequestParameters.class);
    }

    @Test
    void responsesPlanningParameters_areStatelessAndCarryToolDefinitions() {
        OpenAiChatModelProvider provider =
                new OpenAiChatModelProvider(config(), ChatApiFormat.RESPONSES);

        ChatRequestParameters parameters = provider.planningRequestParameters(
                null,
                List.of(toolSpecification()));

        assertThat(parameters).isInstanceOf(OpenAiResponsesChatRequestParameters.class);
        OpenAiResponsesChatRequestParameters responsesParameters =
                (OpenAiResponsesChatRequestParameters) parameters;
        assertThat(responsesParameters.toolSpecifications()).hasSize(1);
        assertThat(responsesParameters.previousResponseId()).isNull();
        assertThat(responsesParameters.promptCacheKey()).isNull();
        assertThat(responsesParameters.store()).isFalse();
    }

    @Test
    void responsesPlanningParameters_doNotSendUnsupportedThinkingParameter() {
        OpenAiChatModelProvider provider =
                new OpenAiChatModelProvider(config(), ChatApiFormat.RESPONSES);

        assertThat(provider.planningRequestParameters(ThinkingLevel.MEDIUM, List.of())).isNull();
        OpenAiResponsesChatRequestParameters parameters =
                (OpenAiResponsesChatRequestParameters) provider.planningRequestParameters(
                        ThinkingLevel.OFF,
                        List.of(toolSpecification()));
        assertThat(parameters.reasoningEffort()).isNull();
        assertThat(parameters.toolSpecifications()).hasSize(1);
    }

    @Test
    void effortDialect_mapsLevelsToReasoningEffort() {
        OpenAiChatModelProvider provider =
                new OpenAiChatModelProvider(config(), ChatApiFormat.CHAT_COMPLETIONS);

        assertThat(effortOf(provider, ThinkingLevel.OFF)).isEqualTo("none");
        assertThat(effortOf(provider, ThinkingLevel.LOW)).isEqualTo("low");
        assertThat(effortOf(provider, ThinkingLevel.MEDIUM)).isEqualTo("medium");
        assertThat(effortOf(provider, ThinkingLevel.HIGH)).isEqualTo("high");
        assertThat(effortOf(provider, ThinkingLevel.XHIGH)).isEqualTo("xhigh");
    }

    @Test
    void effortDialect_xhighUsesConfiguredAliasForOllamaCompat() {
        OpenAiChatModelProvider provider = new OpenAiChatModelProvider(ModelConfig.of(Map.of(
                ModelConfigKey.CHAT_API_KEY, "test-key",
                ModelConfigKey.CHAT_MODEL, "test-model",
                ModelConfigKey.CHAT_THINKING_XHIGH_VALUE, "max")), ChatApiFormat.CHAT_COMPLETIONS);

        assertThat(effortOf(provider, ThinkingLevel.XHIGH)).isEqualTo("max");
    }

    @Test
    void unconfiguredEffortDialect_sendsNoThinkingParameterUntilRequested() {
        OpenAiChatModelProvider provider = new OpenAiChatModelProvider(ModelConfig.of(Map.of(
                ModelConfigKey.CHAT_API_KEY, "test-key",
                ModelConfigKey.CHAT_MODEL, "test-model")), ChatApiFormat.CHAT_COMPLETIONS);

        // 未指定档位且无工具 → 不构造参数，模型级默认生效
        assertThat(provider.planningRequestParameters(null, List.of())).isNull();
        // 有工具但未指定档位 → 只带工具定义，不带思考参数
        OpenAiChatRequestParameters toolsOnly =
                (OpenAiChatRequestParameters) provider.planningRequestParameters(
                        null, List.of(toolSpecification()));
        assertThat(toolsOnly.reasoningEffort()).isNull();
        assertThat(effortOf(provider, ThinkingLevel.LOW)).isEqualTo("low");
    }

    @Test
    void qwenDialect_sendsEnableThinkingWithOptionalBudgets() {
        OpenAiChatModelProvider provider = new OpenAiChatModelProvider(ModelConfig.of(Map.of(
                ModelConfigKey.CHAT_API_KEY, "test-key",
                ModelConfigKey.CHAT_MODEL, "qwen3-test",
                ModelConfigKey.CHAT_PROVIDER, "dashscope",
                ModelConfigKey.CHAT_THINKING_BUDGETS, "1024,8192,24576,32768")),
                ChatApiFormat.CHAT_COMPLETIONS);

        assertThat(customParamsOf(provider, ThinkingLevel.OFF))
                .containsEntry("enable_thinking", false)
                .doesNotContainKey("thinking_budget");
        assertThat(customParamsOf(provider, ThinkingLevel.LOW))
                .containsEntry("enable_thinking", true)
                .containsEntry("thinking_budget", 1024);
        assertThat(customParamsOf(provider, ThinkingLevel.MEDIUM))
                .containsEntry("enable_thinking", true)
                .containsEntry("thinking_budget", 8192);
        assertThat(customParamsOf(provider, ThinkingLevel.HIGH))
                .containsEntry("enable_thinking", true)
                .containsEntry("thinking_budget", 24576);
        assertThat(customParamsOf(provider, ThinkingLevel.XHIGH))
                .containsEntry("enable_thinking", true)
                .containsEntry("thinking_budget", 32768);
    }

    @Test
    void explicitDialectOverride_beatsProviderDerivation() {
        OpenAiChatModelProvider provider = new OpenAiChatModelProvider(ModelConfig.of(Map.of(
                ModelConfigKey.CHAT_API_KEY, "test-key",
                ModelConfigKey.CHAT_MODEL, "test-model",
                ModelConfigKey.CHAT_THINKING_DIALECT, "qwen")), ChatApiFormat.CHAT_COMPLETIONS);

        assertThat(customParamsOf(provider, ThinkingLevel.HIGH))
                .containsEntry("enable_thinking", true);
    }

    @Test
    void invalidThinkingConfig_failsFast() {
        assertThatThrownBy(() -> new OpenAiChatModelProvider(ModelConfig.of(Map.of(
                ModelConfigKey.CHAT_API_KEY, "test-key",
                ModelConfigKey.CHAT_MODEL, "test-model",
                ModelConfigKey.CHAT_THINKING_DIALECT, "sometimes")),
                ChatApiFormat.CHAT_COMPLETIONS))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new OpenAiChatModelProvider(ModelConfig.of(Map.of(
                ModelConfigKey.CHAT_API_KEY, "test-key",
                ModelConfigKey.CHAT_MODEL, "test-model",
                ModelConfigKey.CHAT_THINKING_LEVEL, "sometimes")),
                ChatApiFormat.CHAT_COMPLETIONS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void modelLevelDefaults_followResolveOrder() {
        // qwen 方言未配置 → 保持旧线级行为 enable_thinking=true
        OpenAiChatModelProvider qwenUnconfigured = new OpenAiChatModelProvider(ModelConfig.of(Map.of(
                ModelConfigKey.CHAT_API_KEY, "test-key",
                ModelConfigKey.CHAT_MODEL, "qwen3-test",
                ModelConfigKey.CHAT_PROVIDER, "dashscope")), ChatApiFormat.CHAT_COMPLETIONS);
        OpenAiChatRequestParameters qwenDefaults = modelDefaultsOf(qwenUnconfigured);
        assertThat(qwenDefaults.customParameters()).containsEntry("enable_thinking", true);
        assertThat(qwenDefaults.reasoningEffort()).isNull();

        // 旧 chat.thinking=true → effort 方言迁移为 reasoning_effort=medium
        OpenAiChatModelProvider legacyTrue = new OpenAiChatModelProvider(ModelConfig.of(Map.of(
                ModelConfigKey.CHAT_API_KEY, "test-key",
                ModelConfigKey.CHAT_MODEL, "test-model",
                ModelConfigKey.CHAT_THINKING, "true")), ChatApiFormat.CHAT_COMPLETIONS);
        assertThat(modelDefaultsOf(legacyTrue).reasoningEffort()).isEqualTo("medium");

        // 旧 chat.thinking=false → reasoning_effort=none
        assertThat(modelDefaultsOf(new OpenAiChatModelProvider(
                config(), ChatApiFormat.CHAT_COMPLETIONS)).reasoningEffort())
                .isEqualTo("none");
    }

    @Test
    void requestLevelNullWithTools_keepsModelLevelDefaultsAfterMerge() {
        // effort 方言：模型级默认 none，请求级 null+工具合并后仍保留
        OpenAiChatModelProvider effortProvider =
                new OpenAiChatModelProvider(config(), ChatApiFormat.CHAT_COMPLETIONS);
        ChatRequestParameters mergedEffort = modelDefaultsOf(effortProvider)
                .overrideWith(requestParametersOf(effortProvider, null));
        assertThat(((OpenAiChatRequestParameters) mergedEffort).reasoningEffort()).isEqualTo("none");

        // qwen 方言：模型级默认 MEDIUM（enable_thinking=true + 默认档预算），请求级 null+工具合并后原样保留
        OpenAiChatModelProvider qwenProvider = new OpenAiChatModelProvider(ModelConfig.of(Map.of(
                ModelConfigKey.CHAT_API_KEY, "test-key",
                ModelConfigKey.CHAT_MODEL, "qwen3-test",
                ModelConfigKey.CHAT_PROVIDER, "dashscope",
                ModelConfigKey.CHAT_THINKING_BUDGETS, "1024,8192,24576,32768")),
                ChatApiFormat.CHAT_COMPLETIONS);
        ChatRequestParameters mergedQwen = modelDefaultsOf(qwenProvider)
                .overrideWith(requestParametersOf(qwenProvider, null));
        assertThat(((OpenAiChatRequestParameters) mergedQwen).customParameters())
                .containsEntry("enable_thinking", true)
                .containsEntry("thinking_budget", 8192);
    }

    private static OpenAiChatRequestParameters modelDefaultsOf(OpenAiChatModelProvider provider) {
        return (OpenAiChatRequestParameters) provider.createRawChatModel().defaultRequestParameters();
    }

    private static ChatRequestParameters requestParametersOf(
            OpenAiChatModelProvider provider, ThinkingLevel level) {
        return provider.planningRequestParameters(level, List.of(toolSpecification()));
    }

    @Test
    void qwenDialect_withoutBudgets_sendsEnableThinkingOnly() {
        OpenAiChatModelProvider provider = new OpenAiChatModelProvider(ModelConfig.of(Map.of(
                ModelConfigKey.CHAT_API_KEY, "test-key",
                ModelConfigKey.CHAT_MODEL, "qwen3-test",
                ModelConfigKey.CHAT_PROVIDER, "dashscope")), ChatApiFormat.CHAT_COMPLETIONS);

        assertThat(customParamsOf(provider, ThinkingLevel.XHIGH))
                .containsEntry("enable_thinking", true)
                .doesNotContainKey("thinking_budget");
    }

    @Test
    void thinkingMaxLevel_clampsRequestLevelsDown() {
        OpenAiChatModelProvider provider = new OpenAiChatModelProvider(ModelConfig.of(Map.of(
                ModelConfigKey.CHAT_API_KEY, "test-key",
                ModelConfigKey.CHAT_MODEL, "test-model",
                ModelConfigKey.CHAT_THINKING_MAX_LEVEL, "high")), ChatApiFormat.CHAT_COMPLETIONS);

        assertThat(effortOf(provider, ThinkingLevel.XHIGH)).isEqualTo("high");
        assertThat(effortOf(provider, ThinkingLevel.HIGH)).isEqualTo("high");
    }

    private static String effortOf(OpenAiChatModelProvider provider, ThinkingLevel level) {
        return ((OpenAiChatRequestParameters) provider.planningRequestParameters(level, List.of()))
                .reasoningEffort();
    }

    private static Map<String, Object> customParamsOf(
            OpenAiChatModelProvider provider, ThinkingLevel level) {
        return ((OpenAiChatRequestParameters) provider.planningRequestParameters(level, List.of()))
                .customParameters();
    }

    private static ToolSpecification toolSpecification() {
        return ToolSpecification.builder()
                .name("lookupPerson")
                .description("Looks up a person")
                .parameters(JsonObjectSchema.builder()
                        .additionalProperties(false)
                        .build())
                .build();
    }

    private static ModelConfig config() {
        return ModelConfig.of(Map.of(
                ModelConfigKey.CHAT_API_KEY, "test-key",
                ModelConfigKey.CHAT_BASE_URL, "http://127.0.0.1:1/v1",
                ModelConfigKey.CHAT_MODEL, "test-model",
                ModelConfigKey.CHAT_MAX_TOKENS, "512",
                ModelConfigKey.CHAT_TEMPERATURE, "0.2",
                ModelConfigKey.CHAT_THINKING, "false",
                ModelConfigKey.CHAT_TIMEOUT_SECONDS, "2"));
    }
}

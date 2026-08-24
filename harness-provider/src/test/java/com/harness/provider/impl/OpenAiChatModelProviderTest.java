package com.harness.provider.impl;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiChatModelProviderTest {

    @BeforeEach
    void configureProvider() {
        EnvConfig.init(Map.of(
                EnvKey.MODEL_CHAT_API_KEY, "test-key",
                EnvKey.MODEL_CHAT_BASE_URL, "http://127.0.0.1:1/v1",
                EnvKey.MODEL_CHAT_MODEL, "test-model",
                EnvKey.MODEL_CHAT_MAX_TOKENS, "512",
                EnvKey.MODEL_CHAT_TEMPERATURE, "0.2",
                EnvKey.MODEL_CHAT_THINKING, "false",
                EnvKey.MODEL_CHAT_TIMEOUT_SECONDS, "2"
        ));
    }

    @Test
    void responsesFormat_buildsResponsesModelsForBlockingStructuredAndStreamingCalls() {
        OpenAiChatModelProvider provider =
                new OpenAiChatModelProvider(OpenAiChatApiFormat.RESPONSES);

        assertThat(provider.createRawChatModel(false))
                .isInstanceOf(OpenAiResponsesChatModel.class);
        assertThat(provider.createRawChatModel(true))
                .isInstanceOf(OpenAiResponsesChatModel.class);
        assertThat(provider.streamingModel())
                .isInstanceOf(OpenAiResponsesStreamingChatModel.class);
    }

    @Test
    void chatCompletionsFormat_keepsExistingModels() {
        OpenAiChatModelProvider provider =
                new OpenAiChatModelProvider(OpenAiChatApiFormat.CHAT_COMPLETIONS);

        assertThat(provider.createRawChatModel(false)).isInstanceOf(OpenAiChatModel.class);
        assertThat(provider.streamingModel()).isInstanceOf(OpenAiStreamingChatModel.class);
        assertThat(provider.planningRequestParameters(null, List.of(toolSpecification())))
                .isInstanceOf(OpenAiChatRequestParameters.class);
    }

    @Test
    void responsesPlanningParameters_areStatelessAndCarryToolDefinitions() {
        OpenAiChatModelProvider provider =
                new OpenAiChatModelProvider(OpenAiChatApiFormat.RESPONSES);

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
                new OpenAiChatModelProvider(OpenAiChatApiFormat.RESPONSES);

        assertThat(provider.planningRequestParameters(true, List.of())).isNull();
        OpenAiResponsesChatRequestParameters parameters =
                (OpenAiResponsesChatRequestParameters) provider.planningRequestParameters(
                        false,
                        List.of(toolSpecification()));
        assertThat(parameters.reasoningEffort()).isNull();
        assertThat(parameters.toolSpecifications()).hasSize(1);
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
}

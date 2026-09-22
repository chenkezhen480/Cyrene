package com.harness.provider;

import com.harness.core.modelconfig.ModelConfig;
import com.harness.core.modelconfig.ModelConfigKey;
import com.harness.provider.impl.NoOpChatModelProvider;
import com.harness.provider.impl.ChatApiFormat;
import com.harness.provider.impl.QwenRealtimeModelProvider;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelProviderFactoryTest {

    @Test
    void emptyConfigurationStartsWithUnavailableChatProvider() {
        ChatModelProvider chat = ModelProviderFactory.createChat(ModelConfig.empty());

        assertThat(chat).isInstanceOf(NoOpChatModelProvider.class);
        assertThat(chat.providerName()).isEqualTo("none");
        assertThatThrownBy(() -> chat.chatModel().chat("hello"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Chat model not configured")
                .hasMessageContaining(ModelConfigKey.CHAT_PROVIDER);

        assertThat(ModelProviderFactory.createAll(ModelConfig.empty()).chat())
                .isInstanceOf(NoOpChatModelProvider.class);
    }

    @Test
    void explicitNoneDisablesChatUntilWebConfigurationIsSaved() {
        ModelConfig config = ModelConfig.of(Map.of(ModelConfigKey.CHAT_PROVIDER, "none"));

        assertThat(ModelProviderFactory.createChat(config))
                .isInstanceOf(NoOpChatModelProvider.class);
    }

    @Test
    void validateChatApiFormat_allowsResponsesForOpenAiCompatibleProviders() {
        assertThat(ModelProviderFactory.validateChatApiFormat("openai", "responses"))
                .isEqualTo(ChatApiFormat.RESPONSES);
        assertThat(ModelProviderFactory.validateChatApiFormat("dashscope", " RESPONSES "))
                .isEqualTo(ChatApiFormat.RESPONSES);
        assertThat(ModelProviderFactory.validateChatApiFormat("deepseek", "responses"))
                .isEqualTo(ChatApiFormat.RESPONSES);
    }

    @Test
    void deepSeekDefaultsToMessagesAndAllowsExplicitOpenAiFormats() {
        assertThat(ModelProviderFactory.defaultChatApiFormat("deepseek"))
                .isEqualTo(ChatApiFormat.MESSAGES);
        assertThat(ModelProviderFactory.validateChatApiFormat("deepseek", "messages"))
                .isEqualTo(ChatApiFormat.MESSAGES);
        assertThat(ModelProviderFactory.validateChatApiFormat("deepseek", "chat_completions"))
                .isEqualTo(ChatApiFormat.CHAT_COMPLETIONS);
    }

    @Test
    void messagesFormatIsRejectedForUnrelatedProviders() {
        assertThatThrownBy(() -> ModelProviderFactory.validateChatApiFormat("openai", "messages"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider=deepseek");
    }

    @Test
    void createsDeepSeekMessagesProviderByDefault() {
        ModelConfig config = ModelConfig.of(Map.of(
                ModelConfigKey.CHAT_PROVIDER, "deepseek",
                ModelConfigKey.CHAT_API_KEY, "unit-test-key",
                ModelConfigKey.CHAT_MODEL, "deepseek-flash"));

        ChatModelProvider provider = ModelProviderFactory.createChat(config);

        assertThat(provider.providerName()).isEqualTo("deepseek");
        assertThat(provider.modelName()).isEqualTo("deepseek-flash");
    }

    @Test
    void validateChatApiFormat_keepsChatCompletionsAvailableForOtherProviders() {
        assertThat(ModelProviderFactory.validateChatApiFormat("anthropic", "chat_completions"))
                .isEqualTo(ChatApiFormat.CHAT_COMPLETIONS);
        assertThat(ModelProviderFactory.validateChatApiFormat("ollama", "chat_completions"))
                .isEqualTo(ChatApiFormat.CHAT_COMPLETIONS);
    }

    @Test
    void validateChatApiFormat_rejectsResponsesForNonOpenAiProviders() {
        assertThatThrownBy(() -> ModelProviderFactory.validateChatApiFormat("anthropic", "responses"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires an OpenAI-compatible chat provider")
                .hasMessageContaining("anthropic");
    }

    @Test
    void validateChatApiFormat_rejectsUnknownValues() {
        assertThatThrownBy(() -> ModelProviderFactory.validateChatApiFormat("openai", "response"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ModelConfigKey.CHAT_API_FORMAT)
                .hasMessageContaining("chat_completions, responses, messages");
    }

    @Test
    void configuredContextCapacityOverridesModelNameEstimates() {
        Map<String, String> values = new java.util.HashMap<>(Map.of(
                ModelConfigKey.CHAT_PROVIDER, "openai", ModelConfigKey.CHAT_API_KEY, "unit-test-key",
                ModelConfigKey.CHAT_BASE_URL, "http://localhost:12345/v1", ModelConfigKey.CHAT_MODEL, "qwen-test",
                ModelConfigKey.CHAT_CONTEXT_WINDOW, "1000000"));
        assertThat(ModelProviderFactory.createChat(ModelConfig.of(values)).contextWindow()).isEqualTo(1000000);
        for (String invalid : java.util.List.of("0", "-1")) {
            values.put(ModelConfigKey.CHAT_CONTEXT_WINDOW, invalid);
            assertThatThrownBy(() -> ModelProviderFactory.createChat(ModelConfig.of(values)))
                    .hasMessageContaining("chat.contextWindow").hasMessageContaining("positive");
        }
    }

    @Test
    void optionalProvidersRequireKnownProviderOrExplicitNone() {
        ModelConfig unknown = ModelConfig.of(Map.of(
                ModelConfigKey.VOICE_PROVIDER, "unknown-provider"));

        assertThatThrownBy(() -> ModelProviderFactory.createVoice(unknown))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported voice model provider")
                .hasMessageContaining("unknown-provider");

        ModelConfig none = ModelConfig.of(Map.of(ModelConfigKey.VOICE_PROVIDER, "none"));
        assertThat(ModelProviderFactory.createVoice(none).isTranscribeAvailable())
                .isFalse();
    }

    @Test
    void createsConfiguredQwenRealtimeProvider() {
        ModelConfig config = ModelConfig.of(Map.of(
                ModelConfigKey.REALTIME_PROVIDER, "qwen",
                ModelConfigKey.REALTIME_API_KEY, "unit-test-key",
                ModelConfigKey.REALTIME_BASE_URL, "wss://example.invalid/api-ws/v1/realtime",
                ModelConfigKey.REALTIME_MODEL, "qwen-realtime-test"));

        RealtimeModelProvider provider = ModelProviderFactory.createRealtime(config);

        assertThat(provider).isInstanceOf(QwenRealtimeModelProvider.class);
        assertThat(provider.providerName()).isEqualTo("qwen");
        assertThat(provider.capabilities().toolCalling()).isTrue();
        assertThat(provider.capabilities().interruption()).isTrue();
    }
}

package com.harness.provider;

import com.harness.core.modelconfig.ModelConfig;
import com.harness.core.modelconfig.ModelConfigKey;
import com.harness.provider.impl.NoOpChatModelProvider;
import com.harness.provider.impl.OpenAiChatApiFormat;
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
                .isEqualTo(OpenAiChatApiFormat.RESPONSES);
        assertThat(ModelProviderFactory.validateChatApiFormat("dashscope", " RESPONSES "))
                .isEqualTo(OpenAiChatApiFormat.RESPONSES);
    }

    @Test
    void validateChatApiFormat_keepsChatCompletionsAvailableForOtherProviders() {
        assertThat(ModelProviderFactory.validateChatApiFormat("anthropic", "chat_completions"))
                .isEqualTo(OpenAiChatApiFormat.CHAT_COMPLETIONS);
        assertThat(ModelProviderFactory.validateChatApiFormat("ollama", "chat_completions"))
                .isEqualTo(OpenAiChatApiFormat.CHAT_COMPLETIONS);
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
                .hasMessageContaining("chat_completions, responses");
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
}

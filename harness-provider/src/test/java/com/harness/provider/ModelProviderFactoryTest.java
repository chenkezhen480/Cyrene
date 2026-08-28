package com.harness.provider;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.provider.impl.OpenAiChatApiFormat;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelProviderFactoryTest {

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
                .hasMessageContaining("HARNESS_MODEL_CHAT_API_FORMAT")
                .hasMessageContaining("chat_completions, responses");
    }

    @Test
    void optionalProvidersRequireKnownProviderOrExplicitNone() {
        EnvConfig.init(Map.of(EnvKey.MODEL_VOICE_PROVIDER, "unknown-provider"));

        assertThatThrownBy(() -> ModelProviderFactory.createVoice(EnvConfig.get()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported voice model provider")
                .hasMessageContaining("unknown-provider");

        EnvConfig.init(Map.of(EnvKey.MODEL_VOICE_PROVIDER, "none"));
        assertThat(ModelProviderFactory.createVoice(EnvConfig.get()).isTranscribeAvailable())
                .isFalse();
    }
}

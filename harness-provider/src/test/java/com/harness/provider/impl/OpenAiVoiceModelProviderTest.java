package com.harness.provider.impl;

import com.harness.core.modelconfig.ModelConfig;
import com.harness.core.modelconfig.ModelConfigKey;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiVoiceModelProviderTest {

    @Test
    void defaultsToMp3ForOpenAiCompatibleBackends() {
        OpenAiVoiceModelProvider provider = provider(Map.of());

        assertThat(provider.synthesizeMimeType()).isEqualTo("audio/mpeg");
        assertThat(provider.synthesizeFileExtension()).isEqualTo("mp3");
        assertThat(provider.capabilities().outputFormats()).containsExactly("mp3");
    }

    @Test
    void reportsTheConfiguredFormatSoTheArtifactMatchesItsBytes() {
        OpenAiVoiceModelProvider provider = provider(Map.of(
                ModelConfigKey.VOICE_RESPONSE_FORMAT, "wav"));

        // A wav recording stored as audio/mpeg is a file no player will open by type.
        assertThat(provider.synthesizeMimeType()).isEqualTo("audio/wav");
        assertThat(provider.synthesizeFileExtension()).isEqualTo("wav");
        assertThat(provider.capabilities().outputFormats()).containsExactly("wav");
    }

    @Test
    void mapsOpusToItsContainerExtension() {
        OpenAiVoiceModelProvider provider = provider(Map.of(
                ModelConfigKey.VOICE_RESPONSE_FORMAT, "opus"));

        assertThat(provider.synthesizeMimeType()).isEqualTo("audio/ogg");
        assertThat(provider.synthesizeFileExtension()).isEqualTo("ogg");
    }

    @Test
    void rejectsAnUnknownFormatInsteadOfSendingItUpstream() {
        // Failing at construction beats a 400 from the provider on the first spoken answer.
        assertThatThrownBy(() -> provider(Map.of(ModelConfigKey.VOICE_RESPONSE_FORMAT, "m4a")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ModelConfigKey.VOICE_RESPONSE_FORMAT);
    }

    private static OpenAiVoiceModelProvider provider(Map<String, String> overrides) {
        Map<String, String> values = new HashMap<>();
        values.put(ModelConfigKey.VOICE_API_KEY, "test-key");
        values.putAll(overrides);
        return new OpenAiVoiceModelProvider(ModelConfig.of(values));
    }
}

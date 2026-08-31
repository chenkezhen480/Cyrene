package com.harness.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.Artifact;
import com.harness.provider.VoiceCapabilities;
import com.harness.provider.VoiceModelProvider;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SpeechSynthesisToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void executeOutput_storesSynthesizedSpeechAsAudioArtifact() {
        VoiceModelProvider provider = new StubVoiceProvider();
        AtomicReference<byte[]> storedBytes = new AtomicReference<>();
        Artifact artifact = new Artifact(
                "artifact-1",
                null,
                "speech.mp3",
                Artifact.ArtifactType.AUDIO,
                "audio/mpeg",
                3,
                "speech.mp3",
                Instant.EPOCH);
        SpeechSynthesisTool tool = new SpeechSynthesisTool(
                provider,
                (data, name, mimeType, sessionId) -> {
                    storedBytes.set(data);
                    assertThat(name).endsWith(".mp3");
                    assertThat(mimeType).isEqualTo("audio/mpeg");
                    assertThat(sessionId).isNull();
                    return artifact;
                });

        var output = tool.executeOutput(MAPPER.createObjectNode()
                .put("text", "hello")
                .put("voice", "nova"));

        assertThat(storedBytes.get()).containsExactly(4, 5, 6);
        assertThat(output.artifacts()).containsExactly(artifact);
        assertThat(output.text()).isEqualTo("Speech synthesis completed.");
        assertThat(tool.spec().name()).isEqualTo(SpeechSynthesisTool.TOOL_NAME);
    }

    private static class StubVoiceProvider implements VoiceModelProvider {
        @Override
        public String transcribe(InputStream audio, String mimeType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] synthesize(String text, String voice) {
            assertThat(text).isEqualTo("hello");
            assertThat(voice).isEqualTo("nova");
            return new byte[]{4, 5, 6};
        }

        @Override
        public VoiceCapabilities capabilities() {
            return new VoiceCapabilities(false, true, List.of(), List.of("mp3"));
        }

        @Override
        public String defaultVoice() {
            return "alloy";
        }

        @Override
        public String providerName() {
            return "test";
        }
    }
}

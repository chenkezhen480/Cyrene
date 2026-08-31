package com.harness.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.provider.VoiceCapabilities;
import com.harness.provider.VoiceModelProvider;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AudioTranscriptionToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void executeOutput_transcribesLoadedAudioAsTextToolOutput() {
        VoiceModelProvider provider = new StubVoiceProvider() {
            @Override
            public String transcribe(InputStream audio, String mimeType) {
                assertThat(mimeType).isEqualTo("audio/webm");
                try {
                    assertThat(audio.readAllBytes()).containsExactly(1, 2, 3);
                } catch (java.io.IOException e) {
                    throw new AssertionError(e);
                }
                return "transcribed text";
            }
        };
        AudioTranscriptionTool tool = new AudioTranscriptionTool(
                provider,
                reference -> new AudioTranscriptionTool.AudioSource(
                        new byte[]{1, 2, 3}, "voice.webm", "audio/webm"));

        var output = tool.executeOutput(
                MAPPER.createObjectNode().put("file", "/files/input/voice.webm"));

        assertThat(output.text()).isEqualTo("transcribed text");
        assertThat(output.artifacts()).isEmpty();
        assertThat(tool.spec().name()).isEqualTo(AudioTranscriptionTool.TOOL_NAME);
    }

    private static class StubVoiceProvider implements VoiceModelProvider {
        @Override
        public String transcribe(InputStream audio, String mimeType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] synthesize(String text, String voice) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VoiceCapabilities capabilities() {
            return new VoiceCapabilities(
                    true, false, List.of("audio/webm"), List.of());
        }

        @Override
        public long maxTranscriptionSizeBytes() {
            return 1024;
        }

        @Override
        public String providerName() {
            return "test";
        }
    }
}

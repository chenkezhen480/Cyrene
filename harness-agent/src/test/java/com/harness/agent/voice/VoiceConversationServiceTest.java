package com.harness.agent.voice;

import com.harness.core.model.Artifact;
import com.harness.provider.VoiceCapabilities;
import com.harness.provider.VoiceModelProvider;
import com.harness.tool.builtin.AudioTranscriptionTool;
import com.harness.tool.builtin.SpeechSynthesisTool;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VoiceConversationServiceTest {

    private static final String RECORDING = "/files/input/voice.webm";

    @Test
    void transcribesTheRecordingAndHandsBackPlainText() {
        FakeVoiceProvider provider = new FakeVoiceProvider();
        provider.transcript = "  今天星期几？  ";

        VoiceConversationService.VoiceTurn turn = service(provider).prepare(voiceContext());

        assertThat(provider.transcribeCalls).isEqualTo(1);
        // Trimmed, and nothing else: the agent sees a user message, not a wrapper around one.
        assertThat(turn.transcript()).isEqualTo("今天星期几？");
        assertThat(turn.transcript()).doesNotContain("[Audio File", "transcribe_audio");
    }

    @Test
    void rejectsBeforeTranscribingWhenAsrIsUnavailable() {
        FakeVoiceProvider provider = new FakeVoiceProvider();
        provider.capabilities = new VoiceCapabilities(false, true, List.of("audio/webm"), List.of("mp3"));

        assertThatThrownBy(() -> service(provider).prepare(voiceContext()))
                .isInstanceOf(VoiceConversationService.VoiceException.class)
                .hasMessageContaining("语音识别未配置");

        assertThat(provider.transcribeCalls).isZero();
    }

    @Test
    void rejectsBeforeAnyWorkWhenTtsIsUnavailable() {
        FakeVoiceProvider provider = new FakeVoiceProvider();
        provider.capabilities = new VoiceCapabilities(true, false, List.of("audio/webm"), List.of());

        assertThatThrownBy(() -> service(provider).prepare(voiceContext()))
                .isInstanceOf(VoiceConversationService.VoiceException.class)
                .hasMessageContaining("语音合成未配置");

        // The point of checking TTS up front: a turn must not spend tokens on an answer
        // nobody will ever hear.
        assertThat(provider.transcribeCalls).isZero();
        assertThat(provider.synthesizeCalls).isZero();
    }

    @Test
    void rejectsWhenNoSpeechWasRecognized() {
        FakeVoiceProvider provider = new FakeVoiceProvider();
        provider.transcript = "   ";

        assertThatThrownBy(() -> service(provider).prepare(voiceContext()))
                .isInstanceOf(VoiceConversationService.VoiceException.class)
                .hasMessageContaining("未识别到有效语音内容");
    }

    @Test
    void rejectsWhenTheRecordingReferenceIsMissing() {
        FakeVoiceProvider provider = new FakeVoiceProvider();

        assertThatThrownBy(() -> service(provider).prepare(Map.of("File", RECORDING)))
                .isInstanceOf(VoiceConversationService.VoiceException.class)
                .hasMessageContaining("未收到语音输入");

        assertThat(provider.transcribeCalls).isZero();
    }

    @Test
    void rejectsUnsupportedAudioTypeBeforeCallingTheProvider() {
        FakeVoiceProvider provider = new FakeVoiceProvider();
        provider.audioMimeType = "audio/x-m4a";

        assertThatThrownBy(() -> service(provider).prepare(voiceContext()))
                .isInstanceOf(VoiceConversationService.VoiceException.class)
                .hasMessageContaining("不支持的音频格式");

        assertThat(provider.transcribeCalls).isZero();
    }

    @Test
    void rejectsOversizedAudioBeforeCallingTheProvider() {
        FakeVoiceProvider provider = new FakeVoiceProvider();
        provider.maxTranscriptionSizeBytes = 2;
        provider.audioBytes = new byte[]{1, 2, 3};

        assertThatThrownBy(() -> service(provider).prepare(voiceContext()))
                .isInstanceOf(VoiceConversationService.VoiceException.class)
                .hasMessageContaining("超出上限");

        assertThat(provider.transcribeCalls).isZero();
    }

    @Test
    void rejectsUnreadableRecording() {
        FakeVoiceProvider provider = new FakeVoiceProvider();

        assertThatThrownBy(() -> service(provider).prepare(Map.of(
                VoiceConversationService.CONTEXT_VOICE_INPUT, "/etc/passwd")))
                .isInstanceOf(VoiceConversationService.VoiceException.class)
                .hasMessageContaining("无法读取");

        assertThat(provider.transcribeCalls).isZero();
    }

    @Test
    void surfacesTranscriptionFailureWithoutReachingTheAgent() {
        FakeVoiceProvider provider = new FakeVoiceProvider();
        provider.transcribeFailure = new IllegalStateException("upstream 502");

        assertThatThrownBy(() -> service(provider).prepare(voiceContext()))
                .isInstanceOf(VoiceConversationService.VoiceException.class)
                .hasMessageContaining("语音识别失败")
                .hasMessageContaining("upstream 502");
    }

    @Test
    void synthesizesTheWholeAnswerOnceThroughTheDefaultVoice() {
        FakeVoiceProvider provider = new FakeVoiceProvider();
        CapturingStorer storer = new CapturingStorer();

        Artifact artifact = service(provider, null, storer).synthesize("完整回答", "session-7");

        assertThat(provider.synthesizeCalls).isEqualTo(1);
        assertThat(provider.lastSynthesizedText).isEqualTo("完整回答");
        assertThat(provider.lastVoice).isEqualTo("alloy");
        assertThat(storer.name).startsWith("speech-").endsWith(".mp3");
        assertThat(storer.mimeType).isEqualTo("audio/mpeg");
        // The session binding the tool variant drops.
        assertThat(storer.sessionId).isEqualTo("session-7");
        assertThat(artifact.type()).isEqualTo(Artifact.ArtifactType.AUDIO);
    }

    @Test
    void rejectsBlankAnswerWithoutCallingTheProvider() {
        FakeVoiceProvider provider = new FakeVoiceProvider();

        assertThatThrownBy(() -> service(provider).synthesize("  ", "session-7"))
                .isInstanceOf(VoiceConversationService.VoiceException.class)
                .hasMessageContaining("回答为空");

        assertThat(provider.synthesizeCalls).isZero();
    }

    @Test
    void surfacesSynthesisFailureSoTheTextAnswerCanSurvive() {
        FakeVoiceProvider provider = new FakeVoiceProvider();
        provider.synthesizeFailure = new IllegalStateException("upstream 503");

        assertThatThrownBy(() -> service(provider).synthesize("回答", "session-7"))
                .isInstanceOf(VoiceConversationService.VoiceException.class)
                .hasMessageContaining("语音合成失败")
                .hasMessageContaining("upstream 503");
    }

    @Test
    void treatsAnythingOtherThanVoiceAsText() {
        assertThat(VoiceConversationService.interactionMode(null))
                .isEqualTo(VoiceConversationService.MODE_TEXT);
        assertThat(VoiceConversationService.interactionMode(""))
                .isEqualTo(VoiceConversationService.MODE_TEXT);
        assertThat(VoiceConversationService.interactionMode("TEXT"))
                .isEqualTo(VoiceConversationService.MODE_TEXT);
        assertThat(VoiceConversationService.interactionMode(" voice "))
                .isEqualTo(VoiceConversationService.MODE_VOICE);
    }

    private static Map<String, Object> voiceContext() {
        return Map.of(VoiceConversationService.CONTEXT_VOICE_INPUT, RECORDING);
    }

    private static VoiceConversationService service(FakeVoiceProvider provider) {
        return service(provider, null, null);
    }

    private static VoiceConversationService service(
            FakeVoiceProvider provider,
            AudioTranscriptionTool.AudioSourceLoader loader,
            SpeechSynthesisTool.ArtifactStorer storer
    ) {
        AudioTranscriptionTool.AudioSourceLoader effectiveLoader = loader != null
                ? loader
                : provider::load;
        SpeechSynthesisTool.ArtifactStorer effectiveStorer = storer != null
                ? storer
                : new CapturingStorer();
        return new VoiceConversationService(() -> provider, effectiveLoader, effectiveStorer);
    }

    private static final class CapturingStorer implements SpeechSynthesisTool.ArtifactStorer {
        private String name;
        private String mimeType;
        private String sessionId;

        @Override
        public Artifact store(byte[] data, String name, String mimeType, String sessionId) {
            this.name = name;
            this.mimeType = mimeType;
            this.sessionId = sessionId;
            return new Artifact("artifact-1", sessionId, name, Artifact.inferType(mimeType),
                    mimeType, data.length, "/tmp/" + name, Instant.EPOCH);
        }
    }

    private static final class FakeVoiceProvider implements VoiceModelProvider {
        VoiceCapabilities capabilities = new VoiceCapabilities(
                true, true, List.of("audio/webm", "audio/mpeg"), List.of("mp3"));
        String transcript = "今天星期几？";
        byte[] audioBytes = new byte[]{1, 2, 3};
        String audioMimeType = "audio/webm";
        long maxTranscriptionSizeBytes = 20L * 1024 * 1024;
        RuntimeException transcribeFailure;
        RuntimeException synthesizeFailure;
        int transcribeCalls;
        int synthesizeCalls;
        String lastSynthesizedText;
        String lastVoice;

        AudioTranscriptionTool.AudioSource load(String reference) {
            if (!reference.startsWith("/files/") && !reference.startsWith("/api/artifacts/")) {
                throw new IllegalArgumentException("Audio reference must use /files/: " + reference);
            }
            return new AudioTranscriptionTool.AudioSource(audioBytes, "voice.webm", audioMimeType);
        }

        @Override
        public String transcribe(InputStream audio, String mimeType) {
            transcribeCalls++;
            if (transcribeFailure != null) {
                throw transcribeFailure;
            }
            return transcript;
        }

        @Override
        public byte[] synthesize(String text, String voice) {
            synthesizeCalls++;
            lastSynthesizedText = text;
            lastVoice = voice;
            if (synthesizeFailure != null) {
                throw synthesizeFailure;
            }
            return new byte[]{9, 9, 9};
        }

        @Override
        public VoiceCapabilities capabilities() {
            return capabilities;
        }

        @Override
        public long maxTranscriptionSizeBytes() {
            return maxTranscriptionSizeBytes;
        }

        @Override
        public String providerName() {
            return "fake";
        }
    }
}

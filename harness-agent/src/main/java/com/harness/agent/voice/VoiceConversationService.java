package com.harness.agent.voice;

import com.harness.core.model.Artifact;
import com.harness.provider.VoiceCapabilities;
import com.harness.provider.VoiceModelProvider;
import com.harness.tool.builtin.AudioTranscriptionTool;
import com.harness.tool.builtin.SpeechSynthesisTool;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The fixed runtime pipeline behind one voice turn:
 * recording -> ASR -> (agent runs on the transcript) -> TTS -> playable artifact.
 *
 * <p>This is deliberately not a tool. Whether a microphone recording gets transcribed is a
 * property of how the request arrived, not a decision for the model to make — a model that
 * declines to call a transcription tool silently drops the user's entire input. The
 * {@code transcribe_audio} and {@code synthesize_speech} tools stay registered for the
 * separate case where the user explicitly asks about an audio <em>file</em>.</p>
 *
 * <p>The agent sees only a plain user message. Nothing about the pipeline is visible to it.</p>
 */
public final class VoiceConversationService {

    /**
     * Request-context key holding the microphone recording reference. The recording travels
     * under its own key rather than as one more entry in {@code File} so the pipeline never has
     * to guess which attachment is the voice one by sniffing MIME types — and so a genuinely
     * attached audio file keeps reaching the agent as the ordinary attachment it is.
     */
    public static final String CONTEXT_VOICE_INPUT = "VoiceInput";

    public static final String MODE_TEXT = "TEXT";
    public static final String MODE_VOICE = "VOICE";

    /** Raised for every way a voice turn can fail before or after the agent runs. */
    public static final class VoiceException extends RuntimeException {
        public VoiceException(String message) {
            super(message);
        }

        public VoiceException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The transcript the agent should run on. */
    public record VoiceTurn(String transcript) {}

    private final Supplier<VoiceModelProvider> providerSource;
    private final AudioTranscriptionTool.AudioSourceLoader sourceLoader;
    private final SpeechSynthesisTool.ArtifactStorer artifactStorer;

    public VoiceConversationService(
            Supplier<VoiceModelProvider> providerSource,
            AudioTranscriptionTool.AudioSourceLoader sourceLoader,
            SpeechSynthesisTool.ArtifactStorer artifactStorer
    ) {
        this.providerSource = Objects.requireNonNull(providerSource, "providerSource");
        this.sourceLoader = Objects.requireNonNull(sourceLoader, "sourceLoader");
        this.artifactStorer = Objects.requireNonNull(artifactStorer, "artifactStorer");
    }

    /** Anything that is not exactly {@code VOICE} is a text turn, so old clients stay correct. */
    public static String interactionMode(String rawMode) {
        return rawMode != null && MODE_VOICE.equalsIgnoreCase(rawMode.trim())
                ? MODE_VOICE
                : MODE_TEXT;
    }

    /**
     * Checks both voice capabilities and transcribes the turn's recording.
     *
     * <p>Capabilities are checked before anything else on purpose: discovering at synthesis time
     * that TTS was never configured would mean the turn already spent tokens on an answer it
     * cannot speak.</p>
     *
     * @param requestContext the raw request context, before it is mapped for the agent
     */
    public VoiceTurn prepare(Map<String, Object> requestContext) {
        VoiceModelProvider provider = providerSource.get();
        VoiceCapabilities capabilities = provider.capabilities();
        if (!capabilities.asrAvailable()) {
            throw new VoiceException("语音识别未配置");
        }
        if (!capabilities.ttsAvailable()) {
            throw new VoiceException("语音合成未配置");
        }

        Object rawReference = requestContext != null
                ? requestContext.get(CONTEXT_VOICE_INPUT)
                : null;
        if (!(rawReference instanceof String reference) || reference.isBlank()) {
            throw new VoiceException("未收到语音输入");
        }

        AudioTranscriptionTool.AudioSource source = loadAudio(reference);
        byte[] audio = source.data();
        if (audio.length == 0) {
            throw new VoiceException("语音文件为空");
        }
        if (audio.length > provider.maxTranscriptionSizeBytes()) {
            throw new VoiceException("语音文件超出上限");
        }
        if (!capabilities.acceptedInputMimeTypes().contains(source.mimeType())) {
            throw new VoiceException("不支持的音频格式: " + source.mimeType());
        }

        String transcript;
        try {
            transcript = provider.transcribe(new ByteArrayInputStream(audio), source.mimeType());
        } catch (Exception e) {
            throw new VoiceException("语音识别失败: " + e.getMessage(), e);
        }
        // An empty transcript is a failed turn, not a prompt. Handing the agent a placeholder
        // would make it answer a question the user never asked.
        if (transcript == null || transcript.isBlank()) {
            throw new VoiceException("未识别到有效语音内容");
        }
        return new VoiceTurn(transcript.trim());
    }

    /**
     * Opens a speaker that synthesizes the answer while it is still being written, for a turn
     * whose text the caller is already streaming. Use {@link #synthesize} instead when the
     * answer arrives in one piece.
     */
    public VoiceSpeechStream openSpeechStream(
            Supplier<String> sessionId,
            BooleanSupplier cancelled,
            VoiceSpeechStream.Listener listener
    ) {
        return new VoiceSpeechStream(
                providerSource, artifactStorer, sessionId, cancelled, listener);
    }

    /**
     * Synthesizes the finished answer into a durable artifact. Called once per voice turn, on
     * the complete final output — never per streamed token.
     */
    public Artifact synthesize(String text, String sessionId) {
        if (text == null || text.isBlank()) {
            throw new VoiceException("回答为空，无法合成语音");
        }
        VoiceModelProvider provider = providerSource.get();
        byte[] audio;
        try {
            audio = provider.synthesize(text, provider.defaultVoice());
        } catch (Exception e) {
            throw new VoiceException("语音合成失败: " + e.getMessage(), e);
        }
        if (audio == null || audio.length == 0) {
            throw new VoiceException("语音合成返回空音频");
        }
        return artifactStorer.store(
                audio,
                "speech-" + UUID.randomUUID() + "." + provider.synthesizeFileExtension(),
                provider.synthesizeMimeType(),
                sessionId);
    }

    private AudioTranscriptionTool.AudioSource loadAudio(String reference) {
        try {
            return sourceLoader.load(reference.trim());
        } catch (Exception e) {
            throw new VoiceException("语音文件无法读取: " + e.getMessage(), e);
        }
    }
}

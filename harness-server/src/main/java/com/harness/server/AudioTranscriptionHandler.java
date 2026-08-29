package com.harness.server;

import com.harness.provider.VoiceCapabilities;
import com.harness.provider.VoiceModelProvider;
import com.harness.server.api.ApiErrorCode;
import com.harness.server.api.ApiResponses;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;

/**
 * POST /api/audio/transcriptions
 */
public final class AudioTranscriptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AudioTranscriptionHandler.class);

    private final VoiceModelProvider voiceModelProvider;
    private final long maxAudioBytes;

    public AudioTranscriptionHandler(VoiceModelProvider voiceModelProvider) {
        this(
                voiceModelProvider,
                voiceModelProvider.maxTranscriptionSizeBytes());
    }

    AudioTranscriptionHandler(VoiceModelProvider voiceModelProvider, long maxAudioBytes) {
        this.voiceModelProvider = java.util.Objects.requireNonNull(
                voiceModelProvider, "voiceModelProvider");
        if (maxAudioBytes <= 0) {
            throw new IllegalArgumentException("maxAudioBytes must be positive");
        }
        this.maxAudioBytes = maxAudioBytes;
    }

    public void handle(Context context) {
        VoiceCapabilities capabilities = voiceModelProvider.capabilities();
        if (!capabilities.asrAvailable()) {
            ApiResponses.error(
                    context,
                    503,
                    ApiErrorCode.INTERNAL_ERROR,
                    "ASR is not configured");
            return;
        }

        UploadedFile file = context.uploadedFile("file");
        if (file == null) {
            ApiResponses.error(
                    context,
                    400,
                    ApiErrorCode.INVALID_REQUEST,
                    "No audio file uploaded");
            return;
        }
        if (file.size() <= 0 || file.size() > maxAudioBytes) {
            ApiResponses.error(
                    context,
                    413,
                    ApiErrorCode.INVALID_REQUEST,
                    "Audio file exceeds the configured size limit",
                    Map.of("maxBytes", maxAudioBytes));
            return;
        }

        String mimeType = normalizeMimeType(file.contentType());
        if (!capabilities.acceptedInputMimeTypes().contains(mimeType)) {
            ApiResponses.error(
                    context,
                    415,
                    ApiErrorCode.INVALID_REQUEST,
                    "Unsupported audio type: " + mimeType,
                    Map.of("acceptedInputMimeTypes", capabilities.acceptedInputMimeTypes()));
            return;
        }

        try {
            String text = voiceModelProvider.transcribe(file.content(), mimeType);
            context.json(Map.of("text", text != null ? text : ""));
        } catch (IllegalArgumentException e) {
            ApiResponses.error(context, 400, ApiErrorCode.INVALID_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.warn("[Audio] Transcription failed: {}", e.getMessage());
            ApiResponses.error(
                    context,
                    502,
                    ApiErrorCode.INTERNAL_ERROR,
                    "Audio transcription failed");
        }
    }

    private static String normalizeMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return "application/octet-stream";
        }
        return mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }
}

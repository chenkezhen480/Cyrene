package com.harness.provider.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.provider.VoiceCapabilities;
import com.harness.provider.VoiceModelProvider;
import com.harness.core.modelconfig.ModelConfig;
import com.harness.core.modelconfig.ModelConfigKey;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class OpenAiVoiceModelProvider implements VoiceModelProvider {

    private static final MediaType JSON_TYPE = MediaType.get("application/json");
    private static final int MAX_ERROR_BODY_CHARS = 2_048;

    private static final List<String> INPUT_MIME_TYPES = List.of(
            "audio/mpeg", "audio/mp3", "audio/mp4", "audio/wav", "audio/x-wav",
            "audio/webm", "audio/ogg");
    private static final List<String> OUTPUT_FORMATS = List.of("mp3");

    private final String apiKey;
    private final String baseUrl;
    private final String asrModel;
    private final String ttsModel;
    private final int maxAsrBytes;
    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private String defaultVoice = "alloy";
    private int timeoutSeconds = 120;

    public OpenAiVoiceModelProvider(ModelConfig config) {
        this(configurationFrom(config));
        this.defaultVoice = config.getString(ModelConfigKey.VOICE_DEFAULT_VOICE, "alloy");
        this.timeoutSeconds = config.getInt(ModelConfigKey.VOICE_TIMEOUT_SECONDS, 120);
    }

    private OpenAiVoiceModelProvider(ProviderConfiguration configuration) {
        this(
                configuration.apiKey(),
                configuration.baseUrl(),
                configuration.asrModel(),
                configuration.ttsModel(),
                configuration.maxAsrBytes(),
                configuration.http(),
                new ObjectMapper());
    }

    /**
     * Injectable constructor used by integration tests and alternative bootstrappers.
     */
    public OpenAiVoiceModelProvider(
            String apiKey,
            String baseUrl,
            String asrModel,
            String ttsModel,
            int maxAsrBytes,
            OkHttpClient http,
            ObjectMapper mapper
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        if (maxAsrBytes <= 0) {
            throw new IllegalArgumentException("maxAsrBytes must be positive");
        }
        this.apiKey = apiKey;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.asrModel = requireModel(asrModel, "asrModel");
        this.ttsModel = requireModel(ttsModel, "ttsModel");
        this.maxAsrBytes = maxAsrBytes;
        this.http = java.util.Objects.requireNonNull(http, "http");
        this.mapper = java.util.Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public String transcribe(InputStream audio, String mimeType) {
        if (audio == null) {
            throw new IllegalArgumentException("audio must not be null");
        }
        String normalizedMimeType = normalizeInputMimeType(mimeType);
        try {
            byte[] audioBytes = audio.readNBytes(maxAsrBytes + 1);
            if (audioBytes.length > maxAsrBytes) {
                throw new IllegalArgumentException("Audio exceeds configured ASR size limit");
            }

            RequestBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                            "file",
                            audioFileName(normalizedMimeType),
                            RequestBody.create(audioBytes, MediaType.get(normalizedMimeType)))
                    .addFormDataPart("model", asrModel)
                    .build();

            Request request = authorizedRequest(baseUrl + "/audio/transcriptions")
                    .post(body)
                    .build();

            try (Response response = http.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                requireSuccessful(response, "ASR", responseBody);
                JsonNode root = mapper.readTree(responseBody);
                JsonNode textNode = root.get("text");
                if (textNode == null || !textNode.isTextual()) {
                    throw new IllegalStateException("ASR response is missing string field: text");
                }
                return textNode.asText();
            }
        } catch (IOException e) {
            throw new IllegalStateException("ASR request failed", e);
        }
    }

    @Override
    public byte[] synthesize(String text, String voice) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        String selectedVoice = voice == null || voice.isBlank() ? defaultVoice : voice;
        Request request = buildSpeechRequest(text, selectedVoice);
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw remoteError("TTS", response);
            }
            return response.body() != null ? response.body().bytes() : new byte[0];
        } catch (IOException e) {
            throw new IllegalStateException("TTS request failed", e);
        }
    }

    @Override
    public VoiceCapabilities capabilities() {
        return new VoiceCapabilities(
                true,
                true,
                INPUT_MIME_TYPES,
                OUTPUT_FORMATS);
    }

    @Override
    public boolean isTranscribeAvailable() {
        return true;
    }

    @Override
    public boolean isSynthesizeAvailable() {
        return true;
    }

    @Override
    public String providerName() {
        return "openai";
    }

    @Override
    public int timeoutSeconds() { return timeoutSeconds; }

    @Override
    public long maxTranscriptionSizeBytes() { return maxAsrBytes; }

    @Override
    public String defaultVoice() { return defaultVoice; }

    private Request buildSpeechRequest(String text, String voice) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", ttsModel);
            payload.put("input", text);
            payload.put("voice", voice);
            payload.put("speed", 1.0);
            payload.put("response_format", "mp3");
            return authorizedRequest(baseUrl + "/audio/speech")
                    .post(RequestBody.create(mapper.writeValueAsBytes(payload), JSON_TYPE))
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize TTS request", e);
        }
    }

    private Request.Builder authorizedRequest(String url) {
        return new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey);
    }

    private static String normalizeInputMimeType(String mimeType) {
        String normalized = mimeType == null || mimeType.isBlank()
                ? "audio/mpeg"
                : mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!INPUT_MIME_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported ASR audio type: " + normalized);
        }
        return normalized;
    }

    private static String audioFileName(String mimeType) {
        return switch (mimeType) {
            case "audio/webm" -> "audio.webm";
            case "audio/ogg" -> "audio.ogg";
            case "audio/wav", "audio/x-wav" -> "audio.wav";
            case "audio/mp4" -> "audio.m4a";
            default -> "audio.mp3";
        };
    }

    private static void requireSuccessful(Response response, String operation, String responseBody) {
        if (!response.isSuccessful()) {
            throw new IllegalStateException(operation + " API error " + response.code()
                    + ": " + truncate(responseBody));
        }
    }

    private static IllegalStateException remoteError(String operation, Response response) {
        String body = "";
        try {
            body = response.body() != null ? response.body().string() : "";
        } catch (IOException ignored) {
            // Preserve the status code even if the remote error body cannot be read.
        }
        return new IllegalStateException(operation + " API error " + response.code()
                + ": " + truncate(body));
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_ERROR_BODY_CHARS
                ? value
                : value.substring(0, MAX_ERROR_BODY_CHARS);
    }

    private static String stripTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String requireModel(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static ProviderConfiguration configurationFrom(ModelConfig config) {
        int timeoutSeconds = config.getInt(ModelConfigKey.VOICE_TIMEOUT_SECONDS, 120);
        int maxAsrSizeMb = config.getInt(ModelConfigKey.VOICE_ASR_MAX_SIZE_MB, 20);
        if (timeoutSeconds <= 0 || maxAsrSizeMb <= 0) {
            throw new IllegalStateException("Voice timeout and ASR size limit must be positive");
        }
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(Math.min(timeoutSeconds, 30), TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build();
        return new ProviderConfiguration(
                config.requireString(ModelConfigKey.VOICE_API_KEY),
                config.getString(ModelConfigKey.VOICE_BASE_URL, "https://api.openai.com/v1"),
                config.getString(ModelConfigKey.VOICE_ASR_MODEL, "whisper-1"),
                config.getString(ModelConfigKey.VOICE_TTS_MODEL, "tts-1"),
                Math.multiplyExact(maxAsrSizeMb, 1024 * 1024),
                client);
    }

    private record ProviderConfiguration(
            String apiKey,
            String baseUrl,
            String asrModel,
            String ttsModel,
            int maxAsrBytes,
            OkHttpClient http
    ) {
    }
}

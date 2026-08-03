package com.harness.ai.model.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.ai.model.AudioChunk;
import com.harness.ai.model.AudioStreamCallback;
import com.harness.ai.model.SynthesisRequest;
import com.harness.ai.model.VoiceCapabilities;
import com.harness.ai.model.VoiceModelProvider;
import com.harness.core.model.CancellationToken;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

public class OpenAiVoiceModelProvider implements VoiceModelProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiVoiceModelProvider.class);
    private static final MediaType JSON_TYPE = MediaType.get("application/json");
    private static final int AUDIO_BUFFER_SIZE = 8 * 1024;
    private static final int MAX_ERROR_BODY_CHARS = 2_048;

    private static final List<String> INPUT_MIME_TYPES = List.of(
            "audio/mpeg", "audio/mp3", "audio/mp4", "audio/wav", "audio/x-wav",
            "audio/webm", "audio/ogg");
    private static final List<String> OUTPUT_FORMATS = List.of(
            "mp3", "opus", "aac", "flac", "wav", "pcm");

    private final String apiKey;
    private final String baseUrl;
    private final String asrModel;
    private final String ttsModel;
    private final int maxAsrBytes;
    private final OkHttpClient http;
    private final ObjectMapper mapper;

    public OpenAiVoiceModelProvider() {
        this(configurationFromEnvironment());
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
        SynthesisRequest synthesisRequest = new SynthesisRequest(
                1,
                text,
                voice != null ? voice : "alloy",
                1.0,
                "mp3",
                "audio");
        Request request = buildSpeechRequest(synthesisRequest);
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
    public void streamSynthesize(
            SynthesisRequest request,
            AudioStreamCallback callback,
            CancellationToken cancellationToken
    ) {
        java.util.Objects.requireNonNull(request, "request");
        java.util.Objects.requireNonNull(callback, "callback");
        if (cancellationToken != null && cancellationToken.isCancelled()) {
            throw new CancellationException("TTS request cancelled before start");
        }

        Call call = http.newCall(buildSpeechRequest(request));
        Runnable cancelCall = call::cancel;
        if (cancellationToken != null) {
            cancellationToken.addCancelCallback(cancelCall);
        }

        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                throw remoteError("TTS", response);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IllegalStateException("TTS response body is empty");
            }
            String mimeType = responseMimeType(response, request.responseFormat());
            callback.onStart(request.sequence(), mimeType);
            if ("sse".equalsIgnoreCase(request.streamFormat())) {
                readSseAudio(body.byteStream(), request.sequence(), mimeType, callback, cancellationToken);
            } else if ("audio".equalsIgnoreCase(request.streamFormat())) {
                readRawAudio(body.byteStream(), request.sequence(), mimeType, callback, cancellationToken);
            } else {
                throw new IllegalArgumentException(
                        "Unsupported TTS stream format: " + request.streamFormat());
            }
            callback.onComplete(request.sequence());
        } catch (CancellationException e) {
            notifyError(callback, request.sequence(), e);
            throw e;
        } catch (Exception e) {
            notifyError(callback, request.sequence(), e);
            throw e instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new IllegalStateException("Streaming TTS request failed", e);
        } finally {
            if (cancellationToken != null) {
                cancellationToken.removeCancelCallback(cancelCall);
            }
        }
    }

    @Override
    public VoiceCapabilities capabilities() {
        return new VoiceCapabilities(
                true,
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

    private Request buildSpeechRequest(SynthesisRequest request) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", ttsModel);
            payload.put("input", request.text());
            payload.put("voice", request.voice());
            payload.put("speed", request.speed());
            payload.put("response_format", request.responseFormat());
            if ("sse".equalsIgnoreCase(request.streamFormat())) {
                payload.put("stream_format", "sse");
            }
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

    private void readRawAudio(
            InputStream input,
            long sequence,
            String mimeType,
            AudioStreamCallback callback,
            CancellationToken cancellationToken
    ) throws IOException {
        byte[] buffer = new byte[AUDIO_BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            requireNotCancelled(cancellationToken);
            if (read == 0) {
                continue;
            }
            callback.onChunk(new AudioChunk(
                    sequence,
                    java.util.Arrays.copyOf(buffer, read),
                    mimeType));
        }
    }

    private void readSseAudio(
            InputStream input,
            long sequence,
            String mimeType,
            AudioStreamCallback callback,
            CancellationToken cancellationToken
    ) throws IOException {
        boolean receivedAudio = false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                requireNotCancelled(cancellationToken);
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    continue;
                }
                JsonNode event = mapper.readTree(data);
                String type = event.path("type").asText("");
                if (!type.isEmpty() && !"speech.audio.delta".equals(type)) {
                    continue;
                }
                String encoded = firstText(event, "delta", "audio");
                if (encoded == null || encoded.isBlank()) {
                    continue;
                }
                callback.onChunk(new AudioChunk(
                        sequence,
                        Base64.getDecoder().decode(encoded),
                        mimeType));
                receivedAudio = true;
            }
        }
        if (!receivedAudio) {
            throw new IllegalStateException("Streaming TTS SSE response contained no audio data");
        }
    }

    private static String firstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.isTextual()) {
                return value.asText();
            }
        }
        return null;
    }

    private static void requireNotCancelled(CancellationToken cancellationToken) {
        if (cancellationToken != null && cancellationToken.isCancelled()) {
            throw new CancellationException("TTS request cancelled");
        }
    }

    private static void notifyError(AudioStreamCallback callback, long sequence, Throwable error) {
        try {
            callback.onError(sequence, error);
        } catch (RuntimeException callbackError) {
            log.debug("[Voice] Audio error callback failed: {}", callbackError.getMessage());
        }
    }

    private static String responseMimeType(Response response, String responseFormat) {
        String contentType = response.header("Content-Type");
        if (contentType != null && !contentType.isBlank()
                && !contentType.toLowerCase(Locale.ROOT).startsWith("text/event-stream")) {
            int semicolon = contentType.indexOf(';');
            return semicolon >= 0 ? contentType.substring(0, semicolon).trim() : contentType.trim();
        }
        return switch (responseFormat.toLowerCase(Locale.ROOT)) {
            case "wav" -> "audio/wav";
            case "opus" -> "audio/ogg; codecs=opus";
            case "aac" -> "audio/aac";
            case "flac" -> "audio/flac";
            case "pcm" -> "audio/pcm";
            default -> "audio/mpeg";
        };
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

    private static ProviderConfiguration configurationFromEnvironment() {
        EnvConfig config = EnvConfig.get();
        int timeoutSeconds = config.getInt(EnvKey.MODEL_VOICE_TIMEOUT_SECONDS, 120);
        int maxAsrSizeMb = config.getInt(EnvKey.MODEL_VOICE_ASR_MAX_SIZE_MB, 20);
        if (timeoutSeconds <= 0 || maxAsrSizeMb <= 0) {
            throw new IllegalStateException("Voice timeout and ASR size limit must be positive");
        }
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(Math.min(timeoutSeconds, 30), TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build();
        return new ProviderConfiguration(
                config.requireString(EnvKey.MODEL_VOICE_API_KEY),
                config.getString(EnvKey.MODEL_VOICE_BASE_URL, "https://api.openai.com/v1"),
                config.getString(EnvKey.MODEL_VOICE_ASR_MODEL, "whisper-1"),
                config.getString(EnvKey.MODEL_VOICE_TTS_MODEL, "tts-1"),
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

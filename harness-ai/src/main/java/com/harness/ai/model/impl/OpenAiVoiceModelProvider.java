package com.harness.ai.model.impl;

import com.harness.ai.model.VoiceModelProvider;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

public class OpenAiVoiceModelProvider implements VoiceModelProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiVoiceModelProvider.class);
    private static final MediaType AUDIO_TYPE = MediaType.get("audio/mpeg");

    private final String apiKey;
    private final String baseUrl;
    private final String asrModel;
    private final String ttsModel;
    private final OkHttpClient http;

    public OpenAiVoiceModelProvider() {
        EnvConfig cfg = EnvConfig.get();
        this.apiKey = cfg.requireString(EnvKey.MODEL_VOICE_API_KEY);
        this.baseUrl = cfg.getString(EnvKey.MODEL_VOICE_BASE_URL, "https://api.openai.com/v1");
        this.asrModel = cfg.getString(EnvKey.MODEL_VOICE_ASR_MODEL, "whisper-1");
        this.ttsModel = cfg.getString(EnvKey.MODEL_VOICE_TTS_MODEL, "tts-1");
        this.http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String transcribe(InputStream audio, String mimeType) {
        try {
            byte[] audioBytes = audio.readAllBytes();
            String url = baseUrl + "/audio/transcriptions";

            RequestBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", "audio",
                            RequestBody.create(audioBytes, MediaType.get(mimeType != null ? mimeType : "audio/mpeg")))
                    .addFormDataPart("model", asrModel)
                    .build();

            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .post(body)
                    .build();

            try (Response response = http.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new RuntimeException("ASR error " + response.code() + ": " + respBody);
                }
                return new ObjectMapper().readTree(respBody).get("text").asText();
            }
        } catch (IOException e) {
            throw new RuntimeException("ASR failed: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] synthesize(String text, String voice) {
        try {
            String url = baseUrl + "/audio/speech";
            String bodyJson = new ObjectMapper().writeValueAsString(
                    java.util.Map.of("model", ttsModel, "input", text, "voice", voice != null ? voice : "alloy"));

            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(bodyJson, MediaType.get("application/json")))
                    .build();

            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String err = response.body() != null ? response.body().string() : "";
                    throw new RuntimeException("TTS error " + response.code() + ": " + err);
                }
                return response.body() != null ? response.body().bytes() : new byte[0];
            }
        } catch (IOException e) {
            throw new RuntimeException("TTS failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isTranscribeAvailable() { return true; }

    @Override
    public boolean isSynthesizeAvailable() { return true; }

    @Override
    public String providerName() { return "openai"; }
}

package com.harness.input.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * HTTP adapter for the isolated MarkItDown document-parser worker.
 */
public final class MarkItDownDocumentConversionService
        implements DocumentConversionService {

    private static final String CONVERT_PATH = "/convert";
    private static final int MAX_ERROR_MESSAGE_CHARS = 1_000;

    private final OkHttpClient http;
    private final ObjectMapper objectMapper;
    private final String convertUrl;
    private final String workerToken;

    public static MarkItDownDocumentConversionService fromEnvironment() {
        EnvConfig config = EnvConfig.get();
        int conversionTimeoutSeconds = config.getInt(
                EnvKey.DOCUMENT_PARSER_TIMEOUT_SECONDS, 300);
        int timeoutSeconds = config.getInt(
                EnvKey.DOCUMENT_PARSER_REQUEST_TIMEOUT_SECONDS,
                conversionTimeoutSeconds + 15);
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException(
                    EnvKey.DOCUMENT_PARSER_REQUEST_TIMEOUT_SECONDS + " must be positive");
        }
        if (timeoutSeconds <= conversionTimeoutSeconds) {
            throw new IllegalArgumentException(
                    EnvKey.DOCUMENT_PARSER_REQUEST_TIMEOUT_SECONDS
                            + " must be greater than "
                            + EnvKey.DOCUMENT_PARSER_TIMEOUT_SECONDS);
        }
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build();
        return new MarkItDownDocumentConversionService(
                http,
                new ObjectMapper(),
                config.getString(
                        EnvKey.DOCUMENT_PARSER_URL, "http://localhost:8082"),
                config.getString(EnvKey.DOCUMENT_PARSER_TOKEN, ""));
    }

    public MarkItDownDocumentConversionService(
            OkHttpClient http,
            ObjectMapper objectMapper,
            String workerUrl,
            String workerToken
    ) {
        this.http = Objects.requireNonNull(http, "http");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.convertUrl = normalizeWorkerUrl(workerUrl) + CONVERT_PATH;
        this.workerToken = workerToken == null ? "" : workerToken.trim();
    }

    @Override
    public DocumentConversionResult convert(DocumentConversionRequest conversionRequest) {
        Objects.requireNonNull(conversionRequest, "conversionRequest");
        RequestBody fileBody;
        try {
            fileBody = RequestBody.create(
                    conversionRequest.fileData(),
                    MediaType.get(conversionRequest.mimeType()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid document MIME type: " + conversionRequest.mimeType(), e);
        }

        RequestBody multipartBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                        "file",
                        conversionRequest.fileName(),
                        fileBody)
                .addFormDataPart("fileName", conversionRequest.fileName())
                .addFormDataPart("mimeType", conversionRequest.mimeType())
                .build();

        Request.Builder requestBuilder = new Request.Builder()
                .url(convertUrl)
                .header("Accept", "application/json")
                .post(multipartBody);
        if (!workerToken.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + workerToken);
        }

        try (Response response = http.newCall(requestBuilder.build()).execute()) {
            String responseBody = response.body() == null
                    ? ""
                    : response.body().string();
            if (!response.isSuccessful()) {
                throw remoteFailure(response.code(), responseBody);
            }
            if (responseBody.isBlank()) {
                throw new DocumentConversionException(
                        "Document parser returned an empty response");
            }
            return parseResult(responseBody);
        } catch (DocumentConversionException e) {
            throw e;
        } catch (IOException e) {
            throw new DocumentConversionException(
                    "Document parser request failed: " + e.getMessage(), e);
        }
    }

    private DocumentConversionResult parseResult(String responseBody) {
        try {
            return objectMapper.readValue(
                    responseBody, DocumentConversionResult.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new DocumentConversionException(
                    "Document parser returned an invalid response: " + e.getMessage(), e);
        }
    }

    private DocumentConversionException remoteFailure(
            int statusCode,
            String responseBody
    ) {
        String errorCode = null;
        String message = null;
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode error = root == null ? null : root.get("error");
            if (error != null && error.isObject()) {
                JsonNode codeNode = error.get("code");
                JsonNode messageNode = error.get("message");
                errorCode = codeNode != null && codeNode.isTextual()
                        ? codeNode.asText()
                        : null;
                message = messageNode != null && messageNode.isTextual()
                        ? messageNode.asText()
                        : null;
            }
        } catch (JsonProcessingException ignored) {
            // Preserve the bounded raw body below when the worker error is not JSON.
        }
        if (message == null || message.isBlank()) {
            message = bounded(responseBody);
        }
        if (message.isBlank()) {
            message = "empty error response";
        }
        String codeSuffix = errorCode == null || errorCode.isBlank()
                ? ""
                : " [" + errorCode + "]";
        return new DocumentConversionException(
                "Document parser returned HTTP " + statusCode
                        + codeSuffix + ": " + message,
                statusCode,
                errorCode);
    }

    private static String normalizeWorkerUrl(String workerUrl) {
        if (workerUrl == null || workerUrl.isBlank()) {
            throw new IllegalArgumentException("Document parser URL must not be blank");
        }
        String normalized = workerUrl.trim().replaceAll("/+$", "");
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid document parser URL: " + workerUrl, e);
        }
        String scheme = uri.getScheme();
        if ((!"http".equalsIgnoreCase(scheme)
                && !"https".equalsIgnoreCase(scheme))
                || uri.getHost() == null) {
            throw new IllegalArgumentException(
                    "Document parser URL must be an absolute HTTP(S) URL");
        }
        return normalized;
    }

    private static String bounded(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= MAX_ERROR_MESSAGE_CHARS
                ? trimmed
                : trimmed.substring(0, MAX_ERROR_MESSAGE_CHARS);
    }
}

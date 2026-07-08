package com.harness.ai.model.impl;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpClientBuilderFactory;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HttpClient implementation that tracks active streaming requests
 * and supports cancellation to immediately stop LLM API connections.
 *
 * <p>Registered via ServiceLoader as the default HttpClientBuilder
 * for LangChain4j, replacing the JDK-only implementation.</p>
 */
public class CancellableHttpClient implements HttpClient {

    private static final Logger log = LoggerFactory.getLogger(CancellableHttpClient.class);

    /** Active SSE streaming futures, keyed by calling thread. */
    private static final ConcurrentHashMap<Thread, CompletableFuture<?>> activeFutures = new ConcurrentHashMap<>();

    private final java.net.http.HttpClient delegate;
    private final Duration readTimeout;

    private CancellableHttpClient(Builder builder) {
        this.readTimeout = builder.readTimeout;
        try {
            this.delegate = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(builder.connectTimeout)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create HTTP client", e);
        }
    }

    /**
     * Cancel all active streaming HTTP requests. Called from CancellationToken.
     */
    public static void cancelAll() {
        int count = 0;
        for (var entry : activeFutures.entrySet()) {
            CompletableFuture<?> future = entry.getValue();
            if (!future.isDone()) {
                future.cancel(true);
                count++;
            }
        }
        activeFutures.clear();
        if (count > 0) {
            log.info("[CancellableHttpClient] Cancelled {} active streaming requests", count);
        }
    }

    // ━━━━━━━━ Blocking execute ━━━━━━━━

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) throws HttpException, RuntimeException {
        java.net.http.HttpRequest jdkRequest = toJdkRequest(request);
        try {
            HttpResponse<byte[]> response = delegate.send(jdkRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (!isSuccessful(response)) {
                throw new HttpException(response.statusCode(), readBodyAsString(response));
            }
            return fromJdkResponse(response);
        } catch (HttpException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("HTTP request interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException("HTTP request failed", e);
        }
    }

    // ━━━━━━━━ SSE streaming execute ━━━━━━━━

    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        Thread callerThread = Thread.currentThread();
        java.net.http.HttpRequest jdkRequest = toJdkRequest(request);

        CompletableFuture<Void> future = delegate.sendAsync(jdkRequest, HttpResponse.BodyHandlers.ofInputStream())
                .thenAccept(response -> {
                    if (!isSuccessful(response)) {
                        String body = readInputStreamAsString(response.body());
                        listener.onError(new HttpException(response.statusCode(), body));
                        return;
                    }
                    listener.onOpen(fromJdkStreamResponse(response));
                    try {
                        parser.parse(response.body(), listener);
                        listener.onClose();
                    } catch (Exception e) {
                        listener.onError(e);
                    }
                })
                .exceptionally(ex -> {
                    activeFutures.remove(callerThread);
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof CancellationException) {
                        listener.onError(new RuntimeException("Request cancelled", cause));
                    } else {
                        listener.onError(cause);
                    }
                    return null;
                });

        activeFutures.put(callerThread, future);
        try {
            future.join();
        } finally {
            activeFutures.remove(callerThread);
        }
    }

    // ━━━━━━━━ Helpers ━━━━━━━━

    private java.net.http.HttpRequest toJdkRequest(HttpRequest request) {
        java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(request.url()))
                .timeout(readTimeout);

        // Set headers
        request.headers().forEach((key, values) -> {
            for (String value : values) {
                builder.header(key, value);
            }
        });

        // Set method + body
        HttpMethod method = request.method();
        if (method == HttpMethod.POST) {
            byte[] body = request.body() != null ? request.body().getBytes() : new byte[0];
            builder.POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body));
        } else if (method == HttpMethod.DELETE) {
            builder.DELETE();
        } else {
            builder.GET();
        }

        return builder.build();
    }

    private static boolean isSuccessful(java.net.http.HttpResponse<?> response) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    private static String readBodyAsString(java.net.http.HttpResponse<byte[]> response) {
        return response.body() != null ? new String(response.body()) : "";
    }

    private static String readInputStreamAsString(InputStream is) {
        try {
            return new String(is.readAllBytes());
        } catch (Exception e) {
            return "";
        }
    }

    private static SuccessfulHttpResponse fromJdkResponse(java.net.http.HttpResponse<byte[]> response) {
        Map<String, List<String>> headers = new HashMap<>();
        response.headers().map().forEach((key, values) -> {
            if (key != null) headers.put(key, values);
        });
        return SuccessfulHttpResponse.builder()
                .statusCode(response.statusCode())
                .headers(headers)
                .body(response.body() != null ? new String(response.body()) : null)
                .build();
    }

    private static SuccessfulHttpResponse fromJdkStreamResponse(java.net.http.HttpResponse<?> response) {
        Map<String, List<String>> headers = new HashMap<>();
        response.headers().map().forEach((key, values) -> {
            if (key != null) headers.put(key, values);
        });
        return SuccessfulHttpResponse.builder()
                .statusCode(response.statusCode())
                .headers(headers)
                .build();
    }

    // ━━━━━━━━ Builder + Factory ━━━━━━━━

    public static class Builder implements HttpClientBuilder {
        private Duration connectTimeout = Duration.ofSeconds(30);
        private Duration readTimeout = Duration.ofSeconds(300);

        @Override
        public Duration connectTimeout() { return connectTimeout; }

        @Override
        public HttpClientBuilder connectTimeout(Duration timeout) {
            this.connectTimeout = timeout;
            return this;
        }

        @Override
        public Duration readTimeout() { return readTimeout; }

        @Override
        public HttpClientBuilder readTimeout(Duration timeout) {
            this.readTimeout = timeout;
            return this;
        }

        @Override
        public HttpClient build() {
            return new CancellableHttpClient(this);
        }
    }

    /**
     * ServiceLoader factory. Replaces the default JDK HttpClientBuilder.
     */
    public static class Factory implements HttpClientBuilderFactory {
        @Override
        public HttpClientBuilder create() {
            return new Builder();
        }
    }
}

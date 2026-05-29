package com.harness.ai.model.impl;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * ChatModel decorator that retries on transient errors (429, 503, timeout).
 * Exponential backoff: 1s -> 2s -> 4s, max 3 attempts.
 */
public class RetryingChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(RetryingChatModel.class);
    private static final int MAX_RETRIES = 3;
    private static final long BASE_DELAY_MS = 1000;

    private final ChatModel delegate;

    public RetryingChatModel(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return delegate.chat(request);
            } catch (RuntimeException e) {
                if (!isRetryable(e) || attempt == MAX_RETRIES) {
                    throw e;
                }
                long delay = BASE_DELAY_MS * (1L << (attempt - 1));
                log.warn("[Retry] LLM call failed (attempt {}/{}): {} -- retrying in {}ms",
                        attempt, MAX_RETRIES, e.getMessage(), delay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        // Unreachable: loop always returns or throws on the last attempt
        throw new IllegalStateException("Retry loop exited without result");
    }

    // Override the deprecated chat(ChatMessage...) method for backward compatibility
    @Override
    public ChatResponse chat(ChatMessage... messages) {
        return chat(ChatRequest.builder().messages(List.of(messages)).build());
    }

    private boolean isRetryable(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        // HTTP 429 (rate limit), 503 (service unavailable), timeout, connection errors
        return msg.contains("429")
                || msg.contains("503")
                || msg.contains("rate limit")
                || msg.contains("too many requests")
                || msg.contains("service unavailable")
                || msg.contains("timeout")
                || msg.contains("timed out")
                || msg.contains("connection")
                || msg.contains("socket")
                || msg.contains("eof");
    }
}

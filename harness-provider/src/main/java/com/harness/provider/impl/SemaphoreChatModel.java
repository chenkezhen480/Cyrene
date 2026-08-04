package com.harness.provider.impl;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * ChatModel 装饰器：用外部传入的 Semaphore 控制发往 LLM API 的最大并发数。
 * 429 时阻塞重试（上限 60s），超限抛出明确错误。
 */
public class SemaphoreChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(SemaphoreChatModel.class);

    private static final long RETRY_DELAY_MS = 3000;
    private static final long MAX_RETRY_DURATION_MS = 60_000; // 429 重试总时长上限

    private final ChatModel delegate;
    private final Semaphore semaphore;

    public SemaphoreChatModel(ChatModel delegate, Semaphore semaphore) {
        this.delegate = delegate;
        this.semaphore = semaphore;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for LLM API permit", e);
        }

        try {
            long retryStart = System.currentTimeMillis();
            while (true) {
                try {
                    return delegate.chat(request);
                } catch (RuntimeException e) {
                    if (is429(e)) {
                        long elapsed = System.currentTimeMillis() - retryStart;
                        if (elapsed >= MAX_RETRY_DURATION_MS) {
                            log.error("[Semaphore] 429 retry exhausted after {}ms, giving up", elapsed);
                            throw new RuntimeException(
                                    "LLM API rate limited, please retry later (waited " + (elapsed / 1000) + "s)", e);
                        }
                        log.warn("[Semaphore] 429 rate limited, retrying in {}ms (elapsed {}ms)", RETRY_DELAY_MS, elapsed);
                        Thread.sleep(RETRY_DELAY_MS);
                        continue;
                    }
                    throw e;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during 429 retry", e);
        } finally {
            semaphore.release();
        }
    }

    @Override
    public ChatResponse chat(ChatMessage... messages) {
        return chat(ChatRequest.builder().messages(List.of(messages)).build());
    }

    public int availablePermits() {
        return semaphore.availablePermits();
    }

    public int queuedCount() {
        return semaphore.getQueueLength();
    }

    private boolean is429(Throwable e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        return msg.contains("429") || msg.contains("Too Many Requests") || msg.contains("rate limit");
    }
}

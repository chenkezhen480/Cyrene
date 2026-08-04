package com.harness.provider.impl;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Semaphore;

/**
 * StreamingChatModel 装饰器：用 Semaphore 控制发往 LLM API 的最大并发数。
 * 429 时阻塞重试（上限 60s），超限抛出明确错误。
 */
public class SemaphoreStreamingChatModel implements StreamingChatModel {

    private static final Logger log = LoggerFactory.getLogger(SemaphoreStreamingChatModel.class);

    private static final long RETRY_DELAY_MS = 3000;
    private static final long MAX_RETRY_DURATION_MS = 60_000; // 429 重试总时长上限

    private final StreamingChatModel delegate;
    private final Semaphore semaphore;

    public SemaphoreStreamingChatModel(StreamingChatModel delegate, Semaphore semaphore) {
        this.delegate = delegate;
        this.semaphore = semaphore;
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handler.onError(e);
            return;
        }

        delegate.chat(request, new StreamingChatResponseHandler() {
            private long retryStart = System.currentTimeMillis();

            @Override
            public void onPartialResponse(String token) {
                handler.onPartialResponse(token);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                try {
                    handler.onCompleteResponse(response);
                } finally {
                    semaphore.release();
                }
            }

            @Override
            public void onError(Throwable error) {
                if (is429(error)) {
                    long elapsed = System.currentTimeMillis() - retryStart;
                    if (elapsed >= MAX_RETRY_DURATION_MS) {
                        log.error("[Semaphore-Stream] 429 retry exhausted after {}ms, giving up", elapsed);
                        try {
                            handler.onError(new RuntimeException(
                                    "LLM API rate limited, please retry later (waited " + (elapsed / 1000) + "s)", error));
                        } finally {
                            semaphore.release();
                        }
                        return;
                    }
                    log.warn("[Semaphore-Stream] 429 rate limited, retrying in {}ms (elapsed {}ms)", RETRY_DELAY_MS, elapsed);
                    new Thread(() -> {
                        try {
                            Thread.sleep(RETRY_DELAY_MS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            semaphore.release();
                            handler.onError(error);
                            return;
                        }
                        // 不 release，直接重新发起请求（复用同一个信号量许可）
                        try {
                            delegate.chat(request, this);
                        } catch (RuntimeException e) {
                            // delegate.chat() 同步抛异常时释放信号量，避免泄漏
                            log.error("[Semaphore-Stream] delegate.chat() threw synchronously on retry", e);
                            try {
                                handler.onError(e);
                            } finally {
                                semaphore.release();
                            }
                        }
                    }).start();
                } else {
                    try {
                        handler.onError(error);
                    } finally {
                        semaphore.release();
                    }
                }
            }
        });
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

package com.harness.agent.voice;

import com.harness.provider.AudioChunk;
import com.harness.provider.AudioStreamCallback;
import com.harness.provider.SynthesisRequest;
import com.harness.provider.VoiceModelProvider;
import com.harness.core.model.CancellationToken;
import com.harness.core.model.StreamCallback;
import com.harness.core.model.StreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Converts final-answer tokens into ordered phrase-level TTS requests without
 * blocking the LLM streaming callback.
 */
public final class VoiceOutputCoordinator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(VoiceOutputCoordinator.class);
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final VoiceModelProvider provider;
    private final StreamCallback callback;
    private final CancellationToken cancellationToken;
    private final VoiceOutputSettings settings;
    private final SpeechChunker chunker;
    private final Deque<String> queue = new ArrayDeque<>();
    private final ExecutorService worker;
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private final Object queueMonitor = new Object();
    private final Runnable cancelCallback;

    private volatile boolean inputComplete;
    private volatile boolean closed;
    private volatile boolean failed;
    private long nextSequence = 1;
    private int totalChars;

    public VoiceOutputCoordinator(
            VoiceModelProvider provider,
            StreamCallback callback,
            CancellationToken cancellationToken,
            VoiceOutputSettings settings
    ) {
        this.provider = java.util.Objects.requireNonNull(provider, "provider");
        this.callback = java.util.Objects.requireNonNull(callback, "callback");
        this.cancellationToken = java.util.Objects.requireNonNull(cancellationToken, "cancellationToken");
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
        if (!provider.capabilities().ttsStreamingAvailable()) {
            throw new IllegalStateException(
                    "Configured voice provider does not support streaming TTS: " + provider.providerName());
        }
        this.chunker = new SpeechChunker(settings.minChars(), settings.softChars(), settings.maxChars());
        this.cancelCallback = this::abort;
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "voice-output-" + THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.worker = Executors.newSingleThreadExecutor(threadFactory);
        this.worker.submit(this::drainQueue);
        this.cancellationToken.addCancelCallback(cancelCallback);
    }

    public void accept(String token) {
        if (closed || failed || cancellationToken.isCancelled()) {
            return;
        }
        enqueue(chunker.append(token));
    }

    public void finishAndAwait(Duration timeout) {
        if (closed) {
            return;
        }
        enqueue(chunker.finish());
        synchronized (queueMonitor) {
            inputComplete = true;
            queueMonitor.notifyAll();
        }
        try {
            completion.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            fail("VOICE_OUTPUT_TIMEOUT", "Voice output did not finish in time", e);
            abort();
        } finally {
            close();
        }
    }

    public void abort() {
        cancellationToken.removeCancelCallback(cancelCallback);
        synchronized (queueMonitor) {
            closed = true;
            queue.clear();
            queueMonitor.notifyAll();
        }
        worker.shutdownNow();
        completion.complete(null);
    }

    @Override
    public void close() {
        cancellationToken.removeCancelCallback(cancelCallback);
        if (closed) {
            return;
        }
        closed = true;
        worker.shutdownNow();
    }

    private void enqueue(List<String> phrases) {
        if (phrases.isEmpty()) {
            return;
        }
        synchronized (queueMonitor) {
            for (String phrase : phrases) {
                if (closed || failed) {
                    return;
                }
                totalChars = Math.addExact(totalChars, phrase.length());
                if (totalChars > settings.maxTotalChars()) {
                    fail("VOICE_TEXT_LIMIT_EXCEEDED", "Voice reply exceeds configured text limit", null);
                    return;
                }
                if (queue.size() >= settings.queueCapacity()) {
                    String previous = queue.removeLast();
                    queue.addLast(previous + " " + phrase);
                } else {
                    queue.addLast(phrase);
                }
            }
            queueMonitor.notifyAll();
        }
    }

    private void drainQueue() {
        try {
            while (!closed && !cancellationToken.isCancelled()) {
                String phrase;
                synchronized (queueMonitor) {
                    while (queue.isEmpty() && !inputComplete && !closed) {
                        queueMonitor.wait();
                    }
                    if ((queue.isEmpty() && inputComplete) || closed) {
                        break;
                    }
                    phrase = queue.removeFirst();
                }
                synthesize(nextSequence++, phrase);
                if (failed) {
                    break;
                }
            }
            if (!failed && !cancellationToken.isCancelled()) {
                callback.onEvent(StreamEvent.audioDone());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            fail("VOICE_OUTPUT_FAILED", "Voice output failed", e);
        } finally {
            completion.complete(null);
        }
    }

    private void synthesize(long sequence, String phrase) {
        SynthesisRequest request = new SynthesisRequest(
                sequence,
                phrase,
                settings.defaultVoice(),
                settings.speed(),
                settings.responseFormat(),
                settings.streamFormat());
        provider.streamSynthesize(request, new AudioStreamCallback() {
            @Override
            public void onStart(long currentSequence, String mimeType) {
                callback.onEvent(StreamEvent.audioStart(currentSequence, mimeType));
            }

            @Override
            public void onChunk(AudioChunk chunk) {
                callback.onEvent(StreamEvent.audioDelta(
                        chunk.sequence(),
                        chunk.mimeType(),
                        Base64.getEncoder().encodeToString(chunk.data())));
            }

            @Override
            public void onComplete(long currentSequence) {
                callback.onEvent(StreamEvent.audioChunkDone(currentSequence));
            }

            @Override
            public void onError(long currentSequence, Throwable error) {
                fail("VOICE_PROVIDER_ERROR", "Voice provider request failed", error);
            }
        }, cancellationToken);
    }

    private void fail(String code, String message, Throwable error) {
        if (failed || cancellationToken.isCancelled()) {
            return;
        }
        failed = true;
        synchronized (queueMonitor) {
            queue.clear();
            inputComplete = true;
            queueMonitor.notifyAll();
        }
        if (error != null) {
            log.warn("[Voice] {}: {}", message, error.getMessage());
        }
        callback.onEvent(StreamEvent.audioError(code, message));
    }
}

package com.harness.input.memory;

import com.harness.core.model.MessageBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Asynchronous message writer whose every drained batch is one real database transaction. */
public class MessageWriteWorker {

    private static final Logger log = LoggerFactory.getLogger(MessageWriteWorker.class);
    private static final int BATCH_SIZE = 20;
    private static final long DRAIN_INTERVAL_MS = 500;
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_RETRY_DELAY_MS = 1000;

    private final MessageStore messageStore;
    private final int maxRetries;
    private final long retryDelayMillis;
    private final BlockingQueue<WriteTask> queue = new LinkedBlockingQueue<>();
    private final List<MessageWrite> deadLetterQueue = new ArrayList<>();
    private final ConcurrentHashMap<String, Set<CompletableFuture<Long>>> traceWrites =
            new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object flushLock = new Object();
    private Thread workerThread;

    private record WriteTask(MessageWrite message, CompletableFuture<Long> completion) {
    }

    public MessageWriteWorker(MessageStore messageStore) {
        this(messageStore, DEFAULT_MAX_RETRIES, DEFAULT_RETRY_DELAY_MS);
    }

    MessageWriteWorker(MessageStore messageStore, int maxRetries, long retryDelayMillis) {
        this.messageStore = java.util.Objects.requireNonNull(messageStore, "messageStore");
        if (maxRetries < 1 || retryDelayMillis < 0) {
            throw new IllegalArgumentException("invalid message retry policy");
        }
        this.maxRetries = maxRetries;
        this.retryDelayMillis = retryDelayMillis;
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            workerThread = new Thread(this::run, "message-write-worker");
            workerThread.setDaemon(true);
            workerThread.start();
            log.info("Message write worker started (batchSize={}, interval={}ms)",
                    BATCH_SIZE, DRAIN_INTERVAL_MS);
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            RuntimeException flushFailure = null;
            try {
                flushPending();
            } catch (RuntimeException e) {
                flushFailure = e;
            } finally {
                if (workerThread != null) {
                    workerThread.interrupt();
                    try {
                        workerThread.join(5000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            log.info("Message write worker stopped (remaining={})", queue.size());
            if (flushFailure != null) {
                throw flushFailure;
            }
        }
    }

    public CompletableFuture<Long> submit(
            String sessionId,
            String traceId,
            String role,
            List<MessageBlock> content,
            boolean isSummary
    ) {
        MessageWrite message = new MessageWrite(sessionId, traceId, role, content, isSummary);
        CompletableFuture<Long> completion = new CompletableFuture<>();
        if (traceId != null) {
            traceWrites.computeIfAbsent(traceId, ignored -> ConcurrentHashMap.newKeySet())
                    .add(completion);
        }
        queue.add(new WriteTask(message, completion));
        return completion;
    }

    public int pending() {
        return queue.size();
    }

    /** Flushes queued writes now. A failed transaction is reported to the caller. */
    public int flushPending() {
        synchronized (flushLock) {
            List<WriteTask> pending = new ArrayList<>();
            queue.drainTo(pending);
            if (!pending.isEmpty()) {
                flushUnlocked(pending);
            }
            return pending.size();
        }
    }

    /** Wait until every write registered for a root Trace has committed or failed. */
    public void awaitTrace(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId is required");
        }
        try {
            flushPending();
        } catch (RuntimeException e) {
            traceWrites.remove(traceId);
            throw new MemoryStoreException(
                    "Message transaction failed for root Trace " + traceId, e);
        }
        while (true) {
            Set<CompletableFuture<Long>> current = traceWrites.get(traceId);
            if (current == null || current.isEmpty()) {
                return;
            }
            CompletableFuture<?>[] futures = current.toArray(CompletableFuture[]::new);
            try {
                CompletableFuture.allOf(futures).join();
                current.removeAll(List.of(futures));
                if (current.isEmpty()) {
                    traceWrites.remove(traceId, current);
                }
            } catch (CompletionException e) {
                traceWrites.remove(traceId, current);
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new MemoryStoreException(
                        "Message transaction failed for root Trace " + traceId, cause);
            }
        }
    }

    private void run() {
        while (running.get()) {
            try {
                WriteTask first = queue.poll(DRAIN_INTERVAL_MS, TimeUnit.MILLISECONDS);
                if (first != null) {
                    List<WriteTask> batch = new ArrayList<>(BATCH_SIZE);
                    batch.add(first);
                    queue.drainTo(batch, BATCH_SIZE - 1);
                    flush(batch);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Message write transaction failed: {}", e.getMessage(), e);
            }
        }
        drain();
    }

    private void drain() {
        List<WriteTask> remaining = new ArrayList<>();
        queue.drainTo(remaining);
        if (!remaining.isEmpty()) {
            flush(remaining);
        }
    }

    private void flush(List<WriteTask> batch) {
        synchronized (flushLock) {
            flushUnlocked(batch);
        }
    }

    private void flushUnlocked(List<WriteTask> batch) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                List<Long> ids = messageStore.saveBatch(
                        batch.stream().map(WriteTask::message).toList());
                if (ids.size() != batch.size()) {
                    throw new MemoryStoreException("Message batch result size mismatch");
                }
                for (int index = 0; index < batch.size(); index++) {
                    batch.get(index).completion().complete(ids.get(index));
                }
                log.debug("Committed {} messages in one transaction", batch.size());
                return;
            } catch (RuntimeException e) {
                lastFailure = e;
                log.error("Message transaction attempt {}/{} failed for {} writes: {}",
                        attempt, maxRetries, batch.size(), e.getMessage(), e);
                if (attempt < maxRetries && !waitBeforeRetry()) {
                    lastFailure = new MemoryStoreException(
                            "Message transaction retry interrupted", e);
                    break;
                }
            }
        }
        RuntimeException failure = lastFailure != null
                ? lastFailure
                : new MemoryStoreException("Message transaction failed");
        synchronized (deadLetterQueue) {
            deadLetterQueue.addAll(batch.stream().map(WriteTask::message).toList());
        }
        for (WriteTask task : batch) {
            task.completion().completeExceptionally(failure);
        }
        throw failure;
    }

    private boolean waitBeforeRetry() {
        try {
            Thread.sleep(retryDelayMillis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public List<MessageWrite> getDeadLetterQueue() {
        synchronized (deadLetterQueue) {
            return List.copyOf(deadLetterQueue);
        }
    }

    public int clearDeadLetterQueue() {
        synchronized (deadLetterQueue) {
            int size = deadLetterQueue.size();
            deadLetterQueue.clear();
            return size;
        }
    }
}

package com.harness.preprocess.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Async worker for batch-writing messages to DB.
 * Drains queue every 500ms or when batch reaches 20 entries.
 *
 * Compression summary writes bypass this worker (synchronous in MemoryCompressor)
 * to guarantee ordering: summary must be committed before subsequent messages.
 */
public class MessageWriteWorker {

    private static final Logger log = LoggerFactory.getLogger(MessageWriteWorker.class);

    private static final int BATCH_SIZE = 20;
    private static final long DRAIN_INTERVAL_MS = 500;

    private final MessageStore messageStore;
    private final BlockingQueue<WriteTask> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread workerThread;

    public record WriteTask(String sessionId, String role, String content, boolean isSummary) {}

    public MessageWriteWorker(MessageStore messageStore) {
        this.messageStore = messageStore;
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            workerThread = new Thread(this::run, "message-write-worker");
            workerThread.setDaemon(true);
            workerThread.start();
            log.info("Message write worker started (batchSize={}, interval={}ms)", BATCH_SIZE, DRAIN_INTERVAL_MS);
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            // Flush remaining messages
            drain();
            if (workerThread != null) {
                workerThread.interrupt();
                try {
                    workerThread.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            log.info("Message write worker stopped (remaining={})", queue.size());
        }
    }

    /**
     * Enqueue a message for async DB write. Non-blocking.
     */
    public void submit(String sessionId, String role, String content, boolean isSummary) {
        queue.offer(new WriteTask(sessionId, role, content, isSummary));
    }

    public int pending() {
        return queue.size();
    }

    private void run() {
        while (running.get()) {
            try {
                // Wait for first item, then drain up to BATCH_SIZE
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
                log.error("Message write worker error: {}", e.getMessage(), e);
            }
        }
        // Final drain on shutdown
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
        for (WriteTask task : batch) {
            try {
                messageStore.save(task.sessionId(), task.role(), task.content(), task.isSummary());
            } catch (Exception e) {
                log.error("Failed to write message (session={}, role={}): {}",
                        task.sessionId(), task.role(), e.getMessage(), e);
            }
        }
        log.debug("Flushed {} messages to DB", batch.size());
    }
}

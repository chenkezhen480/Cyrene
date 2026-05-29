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
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000;

    private final MessageStore messageStore;
    private final BlockingQueue<WriteTask> queue = new LinkedBlockingQueue<>();
    private final List<WriteTask> deadLetterQueue = new ArrayList<>();
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
        List<WriteTask> failedTasks = new ArrayList<>();

        // First pass: try to write each message with retry
        for (WriteTask task : batch) {
            if (!writeWithRetry(task)) {
                failedTasks.add(task);
            }
        }

        // Fallback: try to write failed messages individually (synchronous)
        if (!failedTasks.isEmpty()) {
            log.warn("Retrying {} failed messages as individual writes", failedTasks.size());
            List<WriteTask> stillFailed = new ArrayList<>();
            for (WriteTask task : failedTasks) {
                try {
                    messageStore.save(task.sessionId(), task.role(), task.content(), task.isSummary());
                    log.info("Fallback write succeeded for message (session={}, role={})", task.sessionId(), task.role());
                } catch (Exception e) {
                    log.error("Fallback write also failed for message (session={}, role={}, content={}): {}",
                            task.sessionId(), task.role(), truncate(task.content(), 100), e.getMessage(), e);
                    stillFailed.add(task);
                }
            }

            // Add to dead-letter queue if still failing
            if (!stillFailed.isEmpty()) {
                synchronized (deadLetterQueue) {
                    deadLetterQueue.addAll(stillFailed);
                }
                log.error("{} messages added to dead-letter queue (total dead-lettered={})",
                        stillFailed.size(), deadLetterQueue.size());
            }
        }

        log.debug("Flushed {} messages to DB (failed={})", batch.size() - failedTasks.size(), failedTasks.size());
    }

    /**
     * Try to write a single message with up to MAX_RETRIES attempts.
     * Returns true if write succeeded, false if all retries exhausted.
     */
    private boolean writeWithRetry(WriteTask task) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                messageStore.save(task.sessionId(), task.role(), task.content(), task.isSummary());
                return true;
            } catch (Exception e) {
                log.error("Write attempt {}/{} failed for message (session={}, role={}): {}",
                        attempt, MAX_RETRIES, task.sessionId(), task.role(), e.getMessage(), e);
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("Retry interrupted, giving up on message (session={}, role={})", task.sessionId(), task.role());
                        return false;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Get a copy of the dead-letter queue for inspection/recovery.
     */
    public List<WriteTask> getDeadLetterQueue() {
        synchronized (deadLetterQueue) {
            return new ArrayList<>(deadLetterQueue);
        }
    }

    /**
     * Clear the dead-letter queue (e.g., after manual recovery).
     */
    public int clearDeadLetterQueue() {
        synchronized (deadLetterQueue) {
            int size = deadLetterQueue.size();
            deadLetterQueue.clear();
            return size;
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}

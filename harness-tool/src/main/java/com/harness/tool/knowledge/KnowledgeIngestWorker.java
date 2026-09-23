package com.harness.tool.knowledge;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded recovery worker for persistent document Ingest Jobs. */
public final class KnowledgeIngestWorker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestWorker.class);

    private final KnowledgeIngestService ingestService;
    private final int batchSize;
    private final java.util.function.BooleanSupplier ready;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean draining = new AtomicBoolean();

    public KnowledgeIngestWorker(KnowledgeIngestService ingestService) {
        this(ingestService, () -> true);
    }

    public KnowledgeIngestWorker(KnowledgeIngestService ingestService,
                                 java.util.function.BooleanSupplier ready) {
        this.ready = Objects.requireNonNull(ready, "ready");
        this.ingestService = Objects.requireNonNull(ingestService, "ingestService");
        this.batchSize = EnvConfig.get().getInt(EnvKey.KNOWLEDGE_COMPILER_BATCH_SIZE, 100);
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException(
                    "HARNESS_KNOWLEDGE_COMPILER_BATCH_SIZE must be between 1 and 1000");
        }
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "knowledge-ingest-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        int recovered = ingestService.recoverStuck();
        if (recovered > 0) {
            log.warn("Recovered {} stuck Knowledge Ingest Jobs", recovered);
        }
        executor.scheduleWithFixedDelay(this::drainSafely, 0, 5, TimeUnit.SECONDS);
    }

    public void signal() {
        executor.execute(this::drainSafely);
    }

    private void drainSafely() {
        if (!ready.getAsBoolean() || !draining.compareAndSet(false, true)) {
            return;
        }
        try {
            for (int processed = 0; processed < batchSize; processed++) {
                if (!ready.getAsBoolean()) return;
                try {
                    if (!ingestService.processNext()) {
                        return;
                    }
                } catch (RuntimeException failure) {
                    log.error("Knowledge Ingest Job stage failed: {}", failure.getMessage(), failure);
                }
            }
        } finally {
            draining.set(false);
        }
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}

package com.harness.graph.build;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single bounded recovery worker for durable graph mutation Sagas. */
public final class GraphMutationSagaWorker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GraphMutationSagaWorker.class);

    private final GraphMutationSagaService sagaService;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean draining = new AtomicBoolean();

    public GraphMutationSagaWorker(GraphMutationSagaService sagaService) {
        this.sagaService = Objects.requireNonNull(sagaService, "sagaService");
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "graph-mutation-saga-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        int pollSeconds = EnvConfig.get().getInt(EnvKey.GRAPH_MUTATION_POLL_SECONDS, 5);
        if (pollSeconds < 1 || pollSeconds > 3600) {
            throw new IllegalArgumentException(
                    "HARNESS_GRAPH_MUTATION_POLL_SECONDS must be between 1 and 3600");
        }
        int recovered = sagaService.recoverStuck();
        if (recovered > 0) log.warn("Recovered {} stuck graph mutation Sagas", recovered);
        executor.scheduleWithFixedDelay(
                this::drainSafely, 0, pollSeconds, TimeUnit.SECONDS);
    }

    private void drainSafely() {
        if (!draining.compareAndSet(false, true)) return;
        try {
            for (int processed = 0; processed < 100; processed++) {
                try {
                    if (!sagaService.processNext()) return;
                } catch (RuntimeException failure) {
                    log.error("Graph mutation Saga stage failed: {}",
                            failure.getMessage(), failure);
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
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}

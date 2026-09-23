package com.harness.agent.memory;

import com.harness.core.knowledge.KnowledgeIndexTask;
import com.harness.tool.knowledge.authority.KnowledgeIndexOutboxStore;
import com.harness.tool.knowledge.index.KnowledgeIndexProjector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded, retryable projector for the transactional Knowledge index Outbox. */
public final class KnowledgeIndexOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(
            KnowledgeIndexOutboxWorker.class);
    private static final Duration BASE_RETRY_DELAY = Duration.ofMinutes(1);
    private static final Duration MAX_RETRY_DELAY = Duration.ofHours(1);

    private final KnowledgeIndexOutboxStore outboxStore;
    private final KnowledgeIndexProjector projector;
    private final Clock clock;
    private final KnowledgeIndexOutboxSettings settings;
    private final java.util.function.BooleanSupplier ready;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger activeDrains = new AtomicInteger();
    private ScheduledExecutorService executor;

    public KnowledgeIndexOutboxWorker(
            KnowledgeIndexOutboxStore outboxStore,
            KnowledgeIndexProjector projector,
            Clock clock,
            KnowledgeIndexOutboxSettings settings
    ) {
        this(outboxStore, projector, clock, settings, () -> true);
    }

    public KnowledgeIndexOutboxWorker(
            KnowledgeIndexOutboxStore outboxStore,
            KnowledgeIndexProjector projector,
            Clock clock,
            KnowledgeIndexOutboxSettings settings,
            java.util.function.BooleanSupplier ready
    ) {
        this.ready = java.util.Objects.requireNonNull(ready, "ready");
        this.outboxStore = java.util.Objects.requireNonNull(outboxStore, "outboxStore");
        this.projector = java.util.Objects.requireNonNull(projector, "projector");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        executor = Executors.newScheduledThreadPool(
                settings.concurrency(), runnable -> {
                    Thread thread = new Thread(runnable, "knowledge-index-outbox-worker");
                    thread.setDaemon(true);
                    return thread;
                });
        recoverStuck();
        executor.scheduleWithFixedDelay(
                this::signal,
                0,
                settings.pollInterval().toMillis(),
                TimeUnit.MILLISECONDS);
        log.info("Knowledge index Outbox worker started "
                        + "(concurrency={}, batchSize={}, pollSeconds={})",
                settings.concurrency(), settings.batchSize(),
                settings.pollInterval().toSeconds());
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        ScheduledExecutorService current = executor;
        executor = null;
        if (current != null) {
            current.shutdown();
            try {
                if (!current.awaitTermination(30, TimeUnit.SECONDS)) {
                    current.shutdownNow();
                }
            } catch (InterruptedException e) {
                current.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("Knowledge index Outbox worker stopped");
    }

    public void signal() {
        ScheduledExecutorService current = executor;
        if (!running.get() || current == null || !ready.getAsBoolean()) {
            return;
        }
        while (true) {
            int active = activeDrains.get();
            if (active >= settings.concurrency()) {
                return;
            }
            if (activeDrains.compareAndSet(active, active + 1)) {
                current.execute(this::drainBatch);
            }
        }
    }

    public int recoverStuck() {
        Instant now = clock.instant();
        int recovered = outboxStore.recoverStuck(
                now.minus(settings.stuckDuration()), now);
        if (recovered > 0) {
            log.warn("Recovered {} stuck Knowledge index Outbox tasks", recovered);
        }
        return recovered;
    }

    void processClaimedTask(KnowledgeIndexTask task) {
        try {
            projector.project(task);
            outboxStore.markCompleted(task.id(), clock.instant());
            log.info("Knowledge index task {} completed (operation={})",
                    task.id(), task.operation());
        } catch (Exception e) {
            handleFailure(task, e);
        }
    }

    private void drainBatch() {
        boolean reachedLimit = false;
        try {
            int processed = 0;
            while (running.get() && ready.getAsBoolean() && processed < settings.batchSize()) {
                Optional<KnowledgeIndexTask> claimed;
                try {
                    claimed = outboxStore.claimNext(clock.instant());
                } catch (Exception e) {
                    log.error("Failed to claim Knowledge index Outbox task: {}",
                            e.getMessage(), e);
                    return;
                }
                if (claimed.isEmpty()) {
                    return;
                }
                processClaimedTask(claimed.get());
                processed++;
            }
            reachedLimit = processed == settings.batchSize();
        } finally {
            activeDrains.decrementAndGet();
            if (reachedLimit && running.get()) {
                signal();
            }
        }
    }

    private void handleFailure(KnowledgeIndexTask task, Exception failure) {
        String error = boundedError(failure);
        Instant now = clock.instant();
        try {
            if (task.attempts() >= settings.maxAttempts()) {
                outboxStore.markFailed(task.id(), now, error);
                log.error("Knowledge index task {} permanently failed after {} attempts: {}",
                        task.id(), task.attempts(), error, failure);
                return;
            }
            Duration delay = retryDelay(task.attempts());
            outboxStore.reschedule(task.id(), now.plus(delay), error);
            log.warn("Knowledge index task {} rescheduled after attempt {} in {} seconds: {}",
                    task.id(), task.attempts(), delay.toSeconds(), error);
        } catch (Exception persistenceFailure) {
            log.error("Failed to persist Knowledge index failure for task {}: {}",
                    task.id(), persistenceFailure.getMessage(), persistenceFailure);
        }
    }

    static Duration retryDelay(int attempts) {
        int exponent = Math.max(0, Math.min(attempts - 1, 6));
        long seconds = BASE_RETRY_DELAY.toSeconds() * (1L << exponent);
        return Duration.ofSeconds(Math.min(seconds, MAX_RETRY_DELAY.toSeconds()));
    }

    private static String boundedError(Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            message = failure.getClass().getSimpleName();
        }
        return message.length() <= 1024 ? message : message.substring(0, 1024);
    }
}

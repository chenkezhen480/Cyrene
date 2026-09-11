package com.harness.trace.store;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Background scheduler that periodically purges expired audit traces.
 * Runs cleanup on startup, then every {@code intervalMinutes} thereafter.
 */
public class AuditCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuditCleanupScheduler.class);

    private final TraceStore traceStore;
    private final Predicate<String> retainedByKnowledge;
    private final int retentionDays;
    private final long intervalMinutes;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;

    public AuditCleanupScheduler(
            TraceStore traceStore,
            Predicate<String> retainedByKnowledge
    ) {
        this.traceStore = traceStore;
        this.retainedByKnowledge = java.util.Objects.requireNonNull(
                retainedByKnowledge, "retainedByKnowledge");
        this.retentionDays = EnvConfig.get().getInt(EnvKey.AUDIT_RETENTION_DAYS, 30);
        this.intervalMinutes = EnvConfig.get().getInt(EnvKey.MEMORY_CLEANUP_INTERVAL_MINUTES, 60);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        if (retentionDays <= 0) {
            log.info("[Audit] Retention disabled (AUDIT_RETENTION_DAYS={})", retentionDays);
            return;
        }

        log.info("[Audit] Cleanup scheduler started: retentionDays={}, intervalMinutes={}", retentionDays, intervalMinutes);

        // Immediate cleanup on startup
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "audit-cleanup");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::runCleanup, 0, intervalMinutes, TimeUnit.MINUTES);
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        if (scheduler != null) {
            scheduler.shutdown();
            log.info("[Audit] Cleanup scheduler stopped");
        }
    }

    private void runCleanup() {
        try {
            TraceStore.CleanupResult result = traceStore.cleanup(
                    retentionDays, retainedByKnowledge);
            if (result.deleted() > 0 || result.retainedByKnowledge() > 0) {
                log.info("[Audit] Trace cleanup: deleted={}, retainedByKnowledge={}, retentionDays={}",
                        result.deleted(), result.retainedByKnowledge(), retentionDays);
            }
        } catch (Exception e) {
            log.error("[Audit] Cleanup failed: {}", e.getMessage(), e);
        }
    }
}

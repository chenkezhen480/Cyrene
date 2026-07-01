package com.harness.audit.store;

import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background scheduler that periodically purges expired audit traces.
 * Runs cleanup on startup, then every {@code intervalMinutes} thereafter.
 */
public class AuditCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuditCleanupScheduler.class);

    private final TraceStore traceStore;
    private final int retentionDays;
    private final long intervalMinutes;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;

    public AuditCleanupScheduler(TraceStore traceStore) {
        this.traceStore = traceStore;
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
            int deleted = traceStore.cleanup(retentionDays);
            if (deleted > 0) {
                log.debug("[Audit] Cleaned up {} traces older than {} days", deleted, retentionDays);
            }
        } catch (Exception e) {
            log.error("[Audit] Cleanup failed: {}", e.getMessage(), e);
        }
    }
}

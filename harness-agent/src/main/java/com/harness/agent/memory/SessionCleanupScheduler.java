package com.harness.agent.memory;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.tool.skill.SkillRegistry;
import com.harness.input.memory.SessionMessageCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background scheduler that periodically evicts expired in-memory Session state.
 * Runs every HARNESS_MEMORY_CLEANUP_INTERVAL_MINUTES (default 60).
 */
public class SessionCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(SessionCleanupScheduler.class);

    private final SessionMessageCache messageCache;
    private final SkillRegistry skillRegistry;
    private final long intervalMinutes;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;

    public SessionCleanupScheduler(
            SessionMessageCache messageCache,
            SkillRegistry skillRegistry
    ) {
        this.messageCache = messageCache;
        this.skillRegistry = skillRegistry;
        EnvConfig cfg = EnvConfig.get();
        this.intervalMinutes = cfg.getLong(EnvKey.MEMORY_CLEANUP_INTERVAL_MINUTES, 60);
        if (intervalMinutes < 1) {
            throw new IllegalStateException(
                    EnvKey.MEMORY_CLEANUP_INTERVAL_MINUTES + " must be positive");
        }
    }

    /**
     * Start the cleanup scheduler.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "session-cleanup-scheduler");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleAtFixedRate(this::cleanup, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
            log.info("Session cache cleanup scheduler started (interval={}min)",
                    intervalMinutes);
        }
    }

    /**
     * Stop the cleanup scheduler gracefully.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (scheduler != null) {
                scheduler.shutdown();
                try {
                    if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                        scheduler.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    scheduler.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            log.info("Session cleanup scheduler stopped");
        }
    }

    private void cleanup() {
        try {
            int cacheEvicted = messageCache.evictExpired();
            int skillEvicted = skillRegistry.evictExpired();
            if (cacheEvicted > 0 || skillEvicted > 0) {
                log.debug("[Cleanup] Cache eviction: messageCache={}, skillRegistry={}", cacheEvicted, skillEvicted);
            }
            log.debug("[Cleanup] Session cache metrics: {}", messageCache.metricsSnapshot());
        } catch (Exception e) {
            log.error("Error during session cleanup: {}", e.getMessage(), e);
        }
    }
}

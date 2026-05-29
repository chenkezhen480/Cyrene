package com.harness.preprocess.memory;

import com.harness.core.model.Session;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background scheduler that periodically scans for timed-out sessions.
 * Runs every HARNESS_MEMORY_CLEANUP_INTERVAL_MINUTES (default 60).
 *
 * Flow:
 * 1. SessionStore.findTimedOut(timeout) → find all active sessions past timeout
 * 2. Batch close with status=timeout
 * 3. Quality check each → submit worthy ones to refinement worker
 *
 * HARNESS_MEMORY_STORE=none → scheduler does not start.
 */
public class SessionCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(SessionCleanupScheduler.class);

    private final SessionStore sessionStore;
    private final SessionLifecycleManager lifecycleManager;
    private final PreferenceRefinementWorker refinementWorker;
    private final Duration timeout;
    private final long intervalMinutes;
    private final Duration stuckRefinementThreshold;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;

    public SessionCleanupScheduler(SessionStore sessionStore, SessionLifecycleManager lifecycleManager,
                                   PreferenceRefinementWorker refinementWorker) {
        this.sessionStore = sessionStore;
        this.lifecycleManager = lifecycleManager;
        this.refinementWorker = refinementWorker;
        EnvConfig cfg = EnvConfig.get();
        int timeoutMinutes = cfg.getInt(EnvKey.SESSION_TIMEOUT_MINUTES, 30);
        this.timeout = Duration.ofMinutes(timeoutMinutes);
        this.intervalMinutes = cfg.getLong(EnvKey.MEMORY_CLEANUP_INTERVAL_MINUTES, 60);
        int stuckMinutes = cfg.getInt(EnvKey.MEMORY_REFINEMENT_STUCK_MINUTES, 10);
        this.stuckRefinementThreshold = Duration.ofMinutes(stuckMinutes);
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
            log.info("Session cleanup scheduler started (interval={}min, timeout={}min)", intervalMinutes, timeout.toMinutes());
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
            // Step 1: Recover stuck refinements before processing new ones
            resetStuckRefinements();

            List<Session> timedOut = sessionStore.findTimedOut(timeout);
            if (timedOut.isEmpty()) {
                log.debug("No timed-out sessions found");
                return;
            }

            log.info("Found {} timed-out sessions", timedOut.size());
            for (Session session : timedOut) {
                // Check if already claimed or in progress (skip if so)
                if (sessionStore.claimForRefinement(session.id())) {
                    log.info("Claimed session {} for refinement (user={}, lastActive={})",
                            session.id(), session.userId(), session.lastActive());
                    refinementWorker.submit(session.id(), session.userId());
                    log.info("Submitted session {} for preference refinement", session.id());
                } else {
                    log.debug("Session {} already claimed or not pending refinement, skipping", session.id());
                }
            }
        } catch (Exception e) {
            log.error("Error during session cleanup: {}", e.getMessage(), e);
        }
    }

    /**
     * Detect sessions stuck in 'in_progress' refinement status and reset them to 'pending'.
     * A session is considered stuck if refinement_status = 'in_progress' and last_active
     * is older than the configured threshold (default 10 minutes). This handles crash
     * recovery scenarios where refinement was interrupted mid-process.
     */
    void resetStuckRefinements() {
        try {
            List<Session> stuckSessions = sessionStore.findStuckRefinements(stuckRefinementThreshold);
            if (stuckSessions.isEmpty()) {
                log.debug("No stuck refinements found");
                return;
            }

            log.warn("Found {} stuck refinement sessions (threshold={}min), resetting to pending",
                    stuckSessions.size(), stuckRefinementThreshold.toMinutes());
            for (Session session : stuckSessions) {
                sessionStore.resetRefinementToPending(session.id());
                log.warn("Reset stuck refinement for session {} (user={}, lastActive={})",
                        session.id(), session.userId(), session.lastActive());
            }
        } catch (Exception e) {
            log.error("Error during stuck refinement recovery: {}", e.getMessage(), e);
        }
    }
}

package com.harness.core.model;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe token for cancelling in-progress agent runs.
 * Supports both polling (isCancelled) and preemptive interruption (Thread.interrupt).
 * Shared between the HTTP handler (which signals cancellation) and the ReAct loop (which checks it).
 */
public class CancellationToken {

    private volatile boolean cancelled = false;
    private final Set<Thread> trackedThreads = ConcurrentHashMap.newKeySet();

    /**
     * Register the current thread for interruption on cancel.
     * Should be called at the start of any blocking operation (LLM call, tool execution, etc.).
     */
    public void trackCurrentThread() {
        trackedThreads.add(Thread.currentThread());
    }

    /**
     * Unregister the current thread (call when leaving blocking scope).
     */
    public void untrackCurrentThread() {
        trackedThreads.remove(Thread.currentThread());
    }

    /**
     * Register an arbitrary thread for interruption on cancel.
     * Used by SubAgentOrchestrator to register sub-agent worker threads.
     */
    public void trackThread(Thread thread) {
        trackedThreads.add(thread);
    }

    /**
     * Unregister an arbitrary thread.
     */
    public void untrackThread(Thread thread) {
        trackedThreads.remove(thread);
    }

    /**
     * Signal cancellation and interrupt all tracked threads.
     * Safe to call from any thread.
     */
    public void cancel() {
        this.cancelled = true;
        for (Thread t : trackedThreads) {
            t.interrupt();
        }
    }

    /**
     * Check if cancellation has been requested.
     */
    public boolean isCancelled() {
        return cancelled;
    }
}

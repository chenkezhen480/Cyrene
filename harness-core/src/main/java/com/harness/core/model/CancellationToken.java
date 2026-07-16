package com.harness.core.model;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe token for cancelling in-progress agent runs.
 * Supports both polling (isCancelled) and preemptive interruption (Thread.interrupt).
 * Shared between the HTTP handler (which signals cancellation) and the ReAct loop (which checks it).
 */
public class CancellationToken {

    private volatile boolean cancelled = false;
    private final Set<Thread> trackedThreads = ConcurrentHashMap.newKeySet();
    private final List<Runnable> onCancelCallbacks = new CopyOnWriteArrayList<>();
    private volatile Runnable onCancel;

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
     * Register a callback to invoke on cancel (e.g., to cancel OkHttp calls).
     * Only one callback is supported; subsequent calls overwrite.
     * @deprecated Use {@link #addCancelCallback(Runnable)} instead for multiple callbacks.
     */
    @Deprecated
    public void onCancel(Runnable callback) {
        this.onCancel = callback;
    }

    /**
     * Add a callback to invoke on cancel. Supports multiple callbacks.
     * Used for registering CancellableTool.cancel() dynamically.
     */
    public void addCancelCallback(Runnable callback) {
        this.onCancelCallbacks.add(callback);
    }

    /**
     * Remove a previously registered callback.
     */
    public void removeCancelCallback(Runnable callback) {
        this.onCancelCallbacks.remove(callback);
    }

    /**
     * Signal cancellation, interrupt all tracked threads, and invoke all cancel callbacks.
     * Safe to call from any thread.
     */
    public void cancel() {
        this.cancelled = true;
        // Cancel HTTP calls first (closes sockets immediately)
        // Execute all registered callbacks
        for (Runnable cb : onCancelCallbacks) {
            try { cb.run(); } catch (Exception ignored) {}
        }
        // Also execute legacy single callback
        Runnable cb = this.onCancel;
        if (cb != null) {
            try { cb.run(); } catch (Exception ignored) {}
        }
        // Then interrupt threads (for blocking non-HTTP operations)
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

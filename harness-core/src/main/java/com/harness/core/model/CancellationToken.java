package com.harness.core.model;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe token for cancelling in-progress agent runs.
 * Supports both polling (isCancelled) and preemptive interruption (Thread.interrupt).
 * Shared between the HTTP handler (which signals cancellation) and the ReAct loop (which checks it).
 *
 * Supports parent-child relationships: cancelling a parent token also cancels all children.
 */
public class CancellationToken {

    private volatile boolean cancelled = false;
    private final Set<Thread> trackedThreads = ConcurrentHashMap.newKeySet();
    private final List<Runnable> onCancelCallbacks = new CopyOnWriteArrayList<>();
    private volatile Runnable onCancel;

    // Parent-child support
    private final CancellationToken parent;
    private final Set<CancellationToken> children = ConcurrentHashMap.newKeySet();

    /**
     * Create a root cancellation token (no parent).
     */
    public CancellationToken() {
        this.parent = null;
    }

    /**
     * Create a child token linked to a parent.
     * When parent is cancelled, child is also cancelled.
     */
    private CancellationToken(CancellationToken parent) {
        this.parent = parent;
        if (parent != null) {
            parent.children.add(this);
            // If parent is already cancelled, cancel self immediately
            if (parent.isCancelled()) {
                this.cancelled = true;
            }
        }
    }

    /**
     * Create a child token. When parent is cancelled, child is automatically cancelled.
     * Child cancellation does NOT cancel parent.
     */
    public static CancellationToken createChild(CancellationToken parent) {
        return new CancellationToken(parent);
    }

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
     * Used by SubAgentManager to register sub-agent worker threads.
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
     * Also cancels all child tokens.
     * Safe to call from any thread.
     */
    public void cancel() {
        if (this.cancelled) {
            return;  // Already cancelled
        }
        this.cancelled = true;

        // Cancel all children first
        for (CancellationToken child : children) {
            child.cancel();
        }

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
     * Also checks parent chain.
     */
    public boolean isCancelled() {
        if (cancelled) {
            return true;
        }
        // Check parent chain
        if (parent != null && parent.isCancelled()) {
            this.cancelled = true;  // Propagate down
            return true;
        }
        return false;
    }

    /**
     * Get the parent token, or null if this is a root token.
     */
    public CancellationToken getParent() {
        return parent;
    }

    /**
     * Check if this is a child token.
     */
    public boolean isChild() {
        return parent != null;
    }
}

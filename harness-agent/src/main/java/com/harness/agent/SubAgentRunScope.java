package com.harness.agent;

import com.harness.core.model.CancellationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Scope for sub-agent tasks within a single agent run.
 * Isolates tasks by runId to prevent cross-request interference.
 *
 * Lifecycle:
 *   OPEN → OWNER_FINISHED → (scope removed from map)
 */
public class SubAgentRunScope {

    private static final Logger log = LoggerFactory.getLogger(SubAgentRunScope.class);

    /**
     * Scope lifecycle states.
     */
    public enum ScopeState {
        /** Owner agent is still running, tasks can be submitted. */
        OPEN,
        /** Owner agent finished, existing tasks may continue but no new tasks. */
        OWNER_FINISHED
    }

    private final String runId;
    private final ConcurrentHashMap<String, SubAgentTaskRecord> tasks;
    private final AtomicInteger totalSpawns;
    private volatile Instant lastAccessedAt;
    private final AtomicReference<ScopeState> state;

    // Limits - configurable via env
    private final int maxTasksPerRun;

    public SubAgentRunScope(String runId, int maxTasksPerRun) {
        this.runId = runId;
        this.tasks = new ConcurrentHashMap<>();
        this.totalSpawns = new AtomicInteger(0);
        this.lastAccessedAt = Instant.now();
        this.state = new AtomicReference<>(ScopeState.OPEN);
        this.maxTasksPerRun = maxTasksPerRun;
    }

    public String runId() { return runId; }
    public Instant lastAccessedAt() { return lastAccessedAt; }
    public int taskCount() { return tasks.size(); }
    public ScopeState state() { return state.get(); }

    /**
     * Mark owner agent as finished. No new tasks can be submitted after this.
     */
    public void markOwnerFinished() {
        if (state.compareAndSet(ScopeState.OPEN, ScopeState.OWNER_FINISHED)) {
            log.debug("[SubAgentScope] Owner finished for run {}", runId);
        }
    }

    /**
     * Check if scope is still accepting new tasks.
     */
    private boolean isOpen() {
        return state.get() == ScopeState.OPEN;
    }

    /**
     * Check if all tasks are in terminal state.
     */
    public boolean allTasksTerminal() {
        return tasks.values().stream().allMatch(SubAgentTaskRecord::isTerminal);
    }

    /**
     * Register a new task in this scope.
     * Returns null if scope not open, spawn limit exceeded, or duplicate taskId.
     * Uses atomic increment-then-check to prevent TOCTOU race on spawn limit.
     */
    public SubAgentTaskRecord registerTask(SubAgentTask task, CancellationToken taskToken, String ownerSessionId) {
        lastAccessedAt = Instant.now();

        if (!isOpen()) {
            log.warn("[SubAgentScope] Cannot register task in non-open scope {}", runId);
            return null;
        }

        String taskId = task.taskId();
        SubAgentTaskRecord record = new SubAgentTaskRecord(taskId, runId, ownerSessionId, task, taskToken);

        if (tasks.putIfAbsent(taskId, record) != null) {
            log.warn("[SubAgentScope] Duplicate taskId {} in run {}", taskId, runId);
            return null;
        }

        // Atomic increment-then-check to prevent TOCTOU race
        int newCount = totalSpawns.incrementAndGet();
        if (newCount > maxTasksPerRun) {
            tasks.remove(taskId, record);
            totalSpawns.decrementAndGet();
            log.warn("[SubAgentScope] Spawn limit reached for run {}: {}", runId, maxTasksPerRun);
            return null;
        }

        log.debug("[SubAgentScope] Registered task {} in run {} (total: {})", taskId, runId, newCount);
        return record;
    }

    /**
     * Get a task record by ID.
     */
    public SubAgentTaskRecord getTask(String taskId) {
        lastAccessedAt = Instant.now();
        return tasks.get(taskId);
    }

    /**
     * Get multiple task records by IDs.
     */
    public Map<String, SubAgentTaskRecord> getTasks(List<String> taskIds) {
        lastAccessedAt = Instant.now();
        ConcurrentHashMap<String, SubAgentTaskRecord> result = new ConcurrentHashMap<>();
        for (String taskId : taskIds) {
            SubAgentTaskRecord record = tasks.get(taskId);
            if (record != null) {
                result.put(taskId, record);
            }
        }
        return result;
    }

    /**
     * Get all tasks in this scope.
     */
    public Map<String, SubAgentTaskRecord> getAllTasks() {
        lastAccessedAt = Instant.now();
        return Map.copyOf(tasks);
    }

    /**
     * Get tasks by status.
     */
    public List<SubAgentTaskRecord> getTasksByStatus(SubAgentStatus status) {
        lastAccessedAt = Instant.now();
        return tasks.values().stream()
                .filter(r -> r.status().get() == status)
                .toList();
    }

    /**
     * Cancel all pending/running tasks in this scope.
     */
    public void cancelAll() {
        lastAccessedAt = Instant.now();
        for (SubAgentTaskRecord record : tasks.values()) {
            if (!record.isTerminal()) {
                record.requestCancel();
            }
        }
    }

    /**
     * Validate task dependencies.
     * Returns error message if invalid, null if valid.
     */
    public String validateDependencies(SubAgentTask task) {
        List<String> deps = task.dependencies();
        if (deps == null || deps.isEmpty()) {
            return null;
        }

        String taskId = task.taskId();

        for (String depId : deps) {
            // Cannot depend on self
            if (depId.equals(taskId)) {
                return "Task cannot depend on itself";
            }

            // Dependency must exist in scope
            SubAgentTaskRecord dep = tasks.get(depId);
            if (dep == null) {
                return "Dependency not found in scope: " + depId;
            }

            // If dependency is in a non-success terminal state, new task cannot succeed
            SubAgentStatus depStatus = dep.status().get();
            if (depStatus == SubAgentStatus.FAILED) {
                return "Dependency already failed: " + depId;
            }
            if (depStatus == SubAgentStatus.CANCELLED) {
                return "Dependency already cancelled: " + depId;
            }
            if (depStatus == SubAgentStatus.TIMED_OUT) {
                return "Dependency already timed out: " + depId;
            }
        }

        // Check for duplicates
        if (deps.size() != deps.stream().distinct().count()) {
            return "Duplicate dependencies";
        }

        return null;  // Valid
    }
}

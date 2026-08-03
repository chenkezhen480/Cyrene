package com.harness.agent;

import com.harness.ai.model.ChatModelProvider;
import com.harness.ai.model.VisionModelProvider;
import com.harness.ai.model.VoiceModelProvider;
import com.harness.ai.react.ReActEngine;
import com.harness.audit.TraceCollector;
import com.harness.audit.store.TraceStore;
import com.harness.core.model.AgentTrace;
import com.harness.core.model.CancellationToken;
import com.harness.core.model.RiskLevel;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.tool.RunToolCatalog;
import com.harness.tool.HttpApiTool;
import com.harness.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages sub-agent lifecycle with per-run isolation.
 * Replaces the global SubAgentOrchestrator with scoped task management.
 *
 * Key design:
 * - Each request gets its own SubAgentRunScope (per-run isolation)
 * - Each task gets its own CancellationToken (per-task cancellation)
 * - Sub-agents receive an immutable allowlisted tool catalog
 * - Scope lifecycle: OPEN → OWNER_FINISHED → CLOSED
 * - On task completion, submits event to SessionInbox and triggers session resume
 */
public class SubAgentManager {

    private static final Logger log = LoggerFactory.getLogger(SubAgentManager.class);
    private static final Set<String> SUBAGENT_ORCHESTRATION_TOOLS = Set.of(
            "spawn_subagent",
            "await_subagents",
            "get_subagents",
            "cancel_subagents"
    );

    private final ChatModelProvider chatModelProvider;
    private final VisionModelProvider visionModelProvider;
    private final VoiceModelProvider voiceModelProvider;
    private final ToolExecutor toolExecutor;
    private final TraceStore traceStore;

    // Session resume support
    private final SessionInbox sessionInbox;
    private final SessionResumeDispatcher resumeDispatcher;

    // Configurable limits
    private final int maxConcurrent;
    private final int maxTasksPerRun;
    private final long scopeTtlMinutes;
    private final long taskTimeoutSeconds;

    // Per-run scopes, keyed by runId
    private final ConcurrentHashMap<String, SubAgentRunScope> scopes = new ConcurrentHashMap<>();

    // Global executor shared across all runs
    private volatile ExecutorService executor;

    // Scheduled executor for TTL cleanup
    private final ScheduledExecutorService cleanupScheduler;

    // Counter for active tasks (for monitoring)
    private final AtomicInteger activeTasks = new AtomicInteger(0);

    public SubAgentManager(ChatModelProvider chatModelProvider,
                           VisionModelProvider visionModelProvider,
                           VoiceModelProvider voiceModelProvider,
                           ToolExecutor toolExecutor,
                           TraceStore traceStore,
                           SessionInbox sessionInbox,
                           SessionResumeDispatcher resumeDispatcher) {
        this.chatModelProvider = chatModelProvider;
        this.visionModelProvider = visionModelProvider;
        this.voiceModelProvider = voiceModelProvider;
        this.toolExecutor = toolExecutor;
        this.traceStore = traceStore;
        this.sessionInbox = sessionInbox;
        this.resumeDispatcher = resumeDispatcher;

        // Load configurable limits from env
        this.maxConcurrent = EnvConfig.get().getInt(EnvKey.AGENT_MAX_SUBAGENTS, 3);
        this.maxTasksPerRun = EnvConfig.get().getInt(EnvKey.AGENT_MAX_TASKS_PER_RUN, 16);
        this.scopeTtlMinutes = EnvConfig.get().getLong(EnvKey.AGENT_SCOPE_TTL_MINUTES, 30);
        this.taskTimeoutSeconds = EnvConfig.get().getLong(EnvKey.MODEL_CHAT_TIMEOUT_SECONDS, 300);

        // Schedule TTL cleanup every 5 minutes
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "subagent-scope-cleanup");
            t.setDaemon(true);
            return t;
        });
        this.cleanupScheduler.scheduleAtFixedRate(this::cleanupExpiredScopes, 5, 5, TimeUnit.MINUTES);

        log.info("[SubAgentManager] Initialized: maxConcurrent={}, maxTasksPerRun={}, scopeTtlMinutes={}, taskTimeoutSeconds={}",
                maxConcurrent, maxTasksPerRun, scopeTtlMinutes, taskTimeoutSeconds);
    }

    private ExecutorService getOrCreateExecutor() {
        if (executor == null) {
            synchronized (this) {
                if (executor == null) {
                    executor = Executors.newFixedThreadPool(maxConcurrent, r -> {
                        Thread t = new Thread(r, "sub-agent-worker");
                        t.setDaemon(true);
                        return t;
                    });
                    log.debug("[SubAgentManager] Thread pool created: maxConcurrent={}", maxConcurrent);
                }
            }
        }
        return executor;
    }

    /**
     * Open a new scope for a run. Called by AgentOrchestrator at the start of each request.
     */
    public SubAgentRunScope openScope(String runId) {
        SubAgentRunScope scope = new SubAgentRunScope(runId, maxTasksPerRun);
        scopes.put(runId, scope);
        log.debug("[SubAgentManager] Opened scope for run {}", runId);
        return scope;
    }

    /**
     * Mark owner as finished and clean up if all tasks are terminal.
     * Called when the main agent completes its ReAct loop.
     * If tasks are still running, they will continue but scope will be cleaned up on TTL.
     */
    public void finishRun(String runId) {
        SubAgentRunScope scope = scopes.get(runId);
        if (scope == null) {
            return;
        }

        scope.markOwnerFinished();

        // If all tasks are already terminal, clean up immediately
        if (scope.allTasksTerminal()) {
            scopes.remove(runId);
            log.debug("[SubAgentManager] Scope {} cleaned up (all tasks terminal)", runId);
        } else {
            int running = scope.getTasksByStatus(SubAgentStatus.RUNNING).size();
            int queued = scope.getTasksByStatus(SubAgentStatus.QUEUED).size();
            log.info("[SubAgentManager] Scope {} has {} running/{} queued tasks, will cleanup on TTL", runId, running, queued);
        }
    }

    /**
     * Force close and remove a scope, cancelling all tasks.
     * Used for emergency cleanup or on shutdown.
     */
    public void closeScope(String runId) {
        SubAgentRunScope scope = scopes.remove(runId);
        if (scope != null) {
            scope.cancelAll();
            log.debug("[SubAgentManager] Force closed scope for run {} (tasks: {})", runId, scope.taskCount());
        }
    }

    /**
     * Get scope for a run.
     */
    public SubAgentRunScope getScope(String runId) {
        return scopes.get(runId);
    }

    /**
     * Generate a unique task ID.
     */
    public static String generateTaskId() {
        return "sub-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Submit a task for async execution. Returns immediately without blocking.
     * Task is registered in the scope before execution begins.
     *
     * @return the task record, or null if submission failed
     */
    public SubAgentTaskRecord submitTask(AgentRunContext runContext, SubAgentTask task, String sessionId) {
        String runId = runContext.runId();
        SubAgentRunScope scope = scopes.get(runId);

        if (scope == null) {
            log.error("[SubAgentManager] No scope found for run {}", runId);
            return null;
        }

        // Validate dependencies
        String depError = scope.validateDependencies(task);
        if (depError != null) {
            log.warn("[SubAgentManager] Dependency validation failed for task {}: {}", task.taskId(), depError);
            return null;
        }

        // Create task-level cancellation token (linked to parent)
        CancellationToken parentToken = runContext.cancellationToken();
        CancellationToken taskToken = CancellationToken.createChild(parentToken);

        // Register task in scope
        SubAgentTaskRecord record = scope.registerTask(task, taskToken, sessionId);
        if (record == null) {
            return null;  // Scope not open, spawn limit reached, or duplicate
        }

        // Execute async
        executeTask(runContext, record);

        return record;
    }

    /**
     * Execute a task asynchronously with timeout.
     */
    private void executeTask(AgentRunContext runContext, SubAgentTaskRecord record) {
        // Capture credentials from parent thread
        final Map<String, String> parentCredentials = HttpApiTool.getCurrentCredentialsSnapshot();
        final KnowledgeGraphTool.ContextSnapshot graphContext =
                KnowledgeGraphTool.captureCurrentContext();

        CompletableFuture<SubAgentResult> future = CompletableFuture.supplyAsync(() -> {
            Thread currentThread = Thread.currentThread();
            CancellationToken taskToken = record.taskCancellationToken();

            // Register with task cancellation token (not parent)
            taskToken.trackThread(currentThread);

            // Propagate credentials to sub-agent thread
            HttpApiTool.setCurrentCredentials(parentCredentials);
            KnowledgeGraphTool.restoreCurrentContext(graphContext);

            long start = System.currentTimeMillis();
            String taskId = record.taskId();
            activeTasks.incrementAndGet();

            // Mark as running
            if (!record.start()) {
                log.warn("[SubAgentManager] Task {} already in terminal state, skipping", taskId);
                activeTasks.decrementAndGet();
                return record.completion().join();
            }

            log.debug("[SubAgentManager] Executing task: id={}, activeTasks={}", taskId, activeTasks.get());

            try {
                Set<String> allowedTools = new HashSet<>(record.task().tools());
                allowedTools.removeAll(SUBAGENT_ORCHESTRATION_TOOLS);
                RunToolCatalog subAgentToolCatalog =
                        runContext.toolCatalog().allowing(allowedTools);

                // Each sub-agent gets its own ReActEngine with task-specific tools
                ReActEngine engine = new ReActEngine(chatModelProvider, subAgentToolCatalog, toolExecutor,
                        visionModelProvider, voiceModelProvider);

                // Build system prompt from LLM-generated persona + systemPrompt + context
                String systemPrompt = buildSubAgentPrompt(record.task());
                AgentTrace.Builder traceBuilder = AgentTrace.builder();

                // Execute with task-specific cancellation token
                ReActEngine.ReActResult result = engine.execute(systemPrompt, record.task().description(),
                        List.of(), traceBuilder, null, taskToken, null);

                long duration = System.currentTimeMillis() - start;
                log.info("[SubAgentManager] Task {} completed in {}ms, steps={}", taskId, duration, result.steps().size());

                // Persist sub-agent trace and record steps in parent trace
                String subTraceId = saveSubAgentTrace(traceBuilder, record, runContext, result, duration);

                SubAgentResult subResult = SubAgentResult.success(taskId, result.output(), result.steps(), duration, subTraceId);
                record.succeed(subResult);
                return subResult;

            } catch (Exception e) {
                long duration = System.currentTimeMillis() - start;

                // Check if this was a cancellation
                if (record.isCancelRequested() || taskToken.isCancelled()) {
                    log.info("[SubAgentManager] Task {} cancelled after {}ms", taskId, duration);
                    record.markCancelled();
                    return SubAgentResult.failure(taskId, "Cancelled", List.of(), duration);
                }

                log.error("[SubAgentManager] Task {} failed in {}ms: {}", taskId, duration, e.getMessage());
                SubAgentResult failResult = SubAgentResult.failure(taskId, e.getMessage(), List.of(), duration);
                record.fail(failResult);
                return failResult;

            } finally {
                HttpApiTool.clearCurrentCredentials();
                KnowledgeGraphTool.clearCurrentContext();
                taskToken.untrackThread(currentThread);
                activeTasks.decrementAndGet();

                // If scope is owner-finished and all tasks terminal, clean up
                cleanupIfDone(runContext.runId());
            }
        }, getOrCreateExecutor());

        // Apply task-level timeout
        if (taskTimeoutSeconds > 0) {
            future.orTimeout(taskTimeoutSeconds, TimeUnit.SECONDS)
                  .exceptionally(ex -> {
                      if (ex instanceof TimeoutException) {
                          log.warn("[SubAgentManager] Task {} timed out after {}s", record.taskId(), taskTimeoutSeconds);
                          record.markTimedOut();
                          record.taskCancellationToken().cancel();
                          // If detached, submit timeout event to session inbox
                          if (record.isDetached() && record.ownerSessionId() != null) {
                              SubAgentResult timeoutResult = SubAgentResult.failure(
                                      record.taskId(), "Task timed out after " + taskTimeoutSeconds + "s",
                                      List.of(), 0);
                              submitCompletionEvent(record, timeoutResult);
                          }
                      }
                      return null;
                  });
        }

        // On completion, check delivery state to decide how to deliver result
        future.whenComplete((result, error) -> {
            // Don't overwrite result already set by markTimedOut/markCancelled
            if (record.storedResult() == null) {
                if (result != null) {
                    record.storeCompletionResult(result);
                } else {
                    record.storeCompletionResult(SubAgentResult.failure(record.taskId(),
                            error != null ? error.getMessage() : "Task failed", List.of(), 0));
                }
            }

            // Check delivery state to decide what to do
            switch (record.deliveryState().get()) {
                case DETACHED -> {
                    // Await timed out; submit to session inbox for auto-resume
                    if (record.ownerSessionId() != null) {
                        submitCompletionEvent(record, record.storedResult());
                    }
                }
                case INLINE_PENDING -> {
                    // Result ready but no one is waiting yet (race condition fallback)
                    // Don't submit to inbox — await will pick it up
                }
                case INLINE_CONSUMED, SESSION_RESUMED -> {
                    // Already handled, do nothing
                }
            }
        });
    }

    /**
     * Submit a completion event to the session inbox and trigger resume.
     * Uses CAS to ensure each task only submits one event (DETACHED → SESSION_RESUMED).
     */
    private void submitCompletionEvent(SubAgentTaskRecord record, SubAgentResult result) {
        // CAS: DETACHED → SESSION_RESUMED. Prevents duplicate submission.
        if (!record.markSessionResumed()) {
            log.debug("[SubAgentManager] Task {} already session-resumed, skipping duplicate event", record.taskId());
            return;
        }

        String sessionId = record.ownerSessionId();
        String eventId = UUID.randomUUID().toString();

        SessionInbox.SubAgentCompletedEvent event = new SessionInbox.SubAgentCompletedEvent(
                eventId,
                sessionId,
                record.taskId(),
                record.task().description(),
                result,
                java.time.Instant.now(),
                SessionInbox.SubAgentCompletedEvent.EventStatus.PENDING
        );

        sessionInbox.submit(event);
        log.debug("[SubAgentManager] Completion event submitted: sessionId={}, taskId={}", sessionId, record.taskId());

        // Trigger session resume
        resumeDispatcher.requestResume(sessionId);
    }

    /**
     * Clean up scope if owner is finished and all tasks are terminal.
     */
    private void cleanupIfDone(String runId) {
        SubAgentRunScope scope = scopes.get(runId);
        if (scope != null && scope.state() == SubAgentRunScope.ScopeState.OWNER_FINISHED && scope.allTasksTerminal()) {
            scopes.remove(runId);
            log.debug("[SubAgentManager] Scope {} cleaned up (all tasks terminal after owner finished)", runId);
        }
    }

    /**
     * Build system prompt from LLM-generated persona, system instructions, and context.
     * The main agent's LLM is responsible for crafting task-specific prompts.
     */
    private String buildSubAgentPrompt(SubAgentTask task) {
        StringBuilder sb = new StringBuilder();

        // Persona — who this agent is
        if (task.persona() != null && !task.persona().isBlank()) {
            sb.append(task.persona()).append("\n\n");
        }

        // System instructions — methodology, constraints, output format
        if (task.systemPrompt() != null && !task.systemPrompt().isBlank()) {
            sb.append("[Instructions]\n").append(task.systemPrompt()).append("\n\n");
        }

        // Context — relevant background info
        if (task.context() != null && !task.context().isBlank()) {
            sb.append("[Context]\n").append(task.context()).append("\n\n");
        }

        // Task — what to accomplish
        sb.append("[Task]\n").append(task.description()).append("\n");

        return sb.toString();
    }

    /**
     * Persist sub-agent trace to TraceStore.
     * Records input, steps, output, and links to parent via metadata.
     *
     * @return the sub-agent's trace ID, or null if persistence failed
     */
    private String saveSubAgentTrace(AgentTrace.Builder traceBuilder, SubAgentTaskRecord record,
                                     AgentRunContext runContext, ReActEngine.ReActResult result, long durationMs) {
        if (traceStore == null) return null;
        try {
            traceBuilder
                    .sessionId(runContext.sessionId())
                    .inputText(record.task().description())
                    .llmModel(chatModelProvider.modelName())
                    .promptVersion("sub-agent")
                    .steps(result.steps())
                    .finalOutput(result.output())
                    .riskLevel(result.steps().stream()
                            .flatMap(s -> s.toolResults().stream())
                            .anyMatch(r -> !r.success()) ? RiskLevel.MEDIUM : RiskLevel.LOW)
                    .totalDurationMs(durationMs);

            // Link to parent trace via metadata
            java.util.Map<String, String> meta = new java.util.HashMap<>(traceBuilder.build().metadata());
            meta.put("sub_agent_task_id", record.taskId());
            meta.put("parent_run_id", runContext.runId());
            if (runContext.parentTraceId() != null) {
                meta.put("parent_trace_id", runContext.parentTraceId());
            }
            meta.put("sub_agent_persona", record.task().persona() != null ? record.task().persona() : "");
            meta.put("sub_agent_tools", String.join(",", record.task().tools()));
            traceBuilder.metadata(meta);

            AgentTrace subTrace = traceBuilder.build();
            traceStore.save(subTrace);
            log.info("[SubAgentManager] Sub-agent trace saved: taskId={}, traceId={}, steps={}",
                    record.taskId(), subTrace.traceId(), subTrace.steps().size());
            return subTrace.traceId();
        } catch (Exception e) {
            log.warn("[SubAgentManager] Failed to save sub-agent trace for task {}: {}",
                    record.taskId(), e.getMessage());
            return null;
        }
    }

    /**
     * Get count of active tasks (for monitoring).
     */
    public int getActiveTaskCount() {
        return activeTasks.get();
    }

    /**
     * Get count of active scopes (for monitoring).
     */
    public int getActiveScopeCount() {
        return scopes.size();
    }

    /**
     * Shutdown the executor service and clean up scopes.
     */
    public void shutdown() {
        // Cancel all pending tasks
        for (SubAgentRunScope scope : scopes.values()) {
            scope.cancelAll();
        }
        scopes.clear();

        // Shutdown cleanup scheduler
        cleanupScheduler.shutdown();
        try {
            if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("[SubAgentManager] Shut down");
    }

    /**
     * Clean up scopes that have been in OWNER_FINISHED state longer than TTL.
     * Running tasks are cancelled and their events submitted to inbox.
     */
    private void cleanupExpiredScopes() {
        java.time.Instant now = java.time.Instant.now();
        int cleaned = 0;

        for (Map.Entry<String, SubAgentRunScope> entry : scopes.entrySet()) {
            SubAgentRunScope scope = entry.getValue();
            if (scope.state() == SubAgentRunScope.ScopeState.OWNER_FINISHED) {
                long elapsedMinutes = java.time.Duration.between(scope.lastAccessedAt(), now).toMinutes();
                if (elapsedMinutes >= scopeTtlMinutes) {
                    // Cancel remaining tasks and submit timeout events
                    for (SubAgentTaskRecord record : scope.getAllTasks().values()) {
                        if (!record.isTerminal()) {
                            record.markTimedOut();
                            record.taskCancellationToken().cancel();
                            if (record.ownerSessionId() != null) {
                                SubAgentResult timeoutResult = SubAgentResult.failure(
                                        record.taskId(), "Task timed out (scope TTL expired)", List.of(), 0);
                                submitCompletionEvent(record, timeoutResult);
                            }
                        }
                    }
                    scopes.remove(entry.getKey());
                    cleaned++;
                }
            }
        }

        if (cleaned > 0) {
            log.info("[SubAgentManager] Cleaned up {} expired scopes", cleaned);
        }
    }
}

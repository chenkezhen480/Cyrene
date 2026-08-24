package com.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.context.KnowledgeAccessService;
import com.harness.core.model.ArtifactStore;
import com.harness.react.ReActLoop;
import com.harness.react.ReActLoopFactory;
import com.harness.react.ReActRequest;
import com.harness.react.ReActResult;
import com.harness.core.model.CancellationToken;
import com.harness.core.model.FinalOutputContract;
import com.harness.core.model.RiskLevel;
import com.harness.core.runtime.RunTrace;
import com.harness.core.runtime.RunTraceFactory;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.tool.RunToolCatalog;
import com.harness.tool.HttpApiTool;
import com.harness.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
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
    private final ReActLoopFactory reActLoopFactory;
    private final RunTraceFactory traceFactory;
    private final ToolExecutor toolExecutor;

    // Session resume support
    private final SessionInbox sessionInbox;
    private final SessionResumeDispatcher resumeDispatcher;
    private final SubAgentCompletionContractValidator completionContractValidator;

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

    public SubAgentManager(ReActLoopFactory reActLoopFactory,
                           RunTraceFactory traceFactory,
                           ToolExecutor toolExecutor,
                           ArtifactStore artifactStore,
                           SessionInbox sessionInbox,
                           SessionResumeDispatcher resumeDispatcher) {
        this.reActLoopFactory = java.util.Objects.requireNonNull(reActLoopFactory, "reActLoopFactory");
        this.traceFactory = java.util.Objects.requireNonNull(traceFactory, "traceFactory");
        this.toolExecutor = toolExecutor;
        this.sessionInbox = sessionInbox;
        this.resumeDispatcher = resumeDispatcher;
        this.completionContractValidator = new SubAgentCompletionContractValidator(
                artifactStore, new ObjectMapper());

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
            throw new IllegalStateException("No sub-agent scope found for run " + runId);
        }

        completionContractValidator.validateTaskDefinition(task, runContext.toolCatalog());

        // Validate dependencies
        String depError = scope.validateDependencies(task);
        if (depError != null) {
            throw new IllegalArgumentException(depError);
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
        final KnowledgeAccessService.ContextSnapshot knowledgeContext =
                KnowledgeAccessService.captureCurrentContext();

        CompletableFuture<SubAgentResult> future = CompletableFuture.supplyAsync(() -> {
            Thread currentThread = Thread.currentThread();
            CancellationToken taskToken = record.taskCancellationToken();

            // Register with task cancellation token (not parent)
            taskToken.trackThread(currentThread);

            // Propagate credentials to sub-agent thread
            HttpApiTool.setCurrentCredentials(parentCredentials);
            KnowledgeGraphTool.restoreCurrentContext(graphContext);
            KnowledgeAccessService.restoreCurrentContext(knowledgeContext);

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
                RunToolCatalog subAgentToolCatalog =
                        runContext.toolCatalog().allowing(record.task().tools());

                ReActLoop reActLoop = reActLoopFactory.create(subAgentToolCatalog, toolExecutor);

                // Build system prompt from LLM-generated persona + systemPrompt + context
                String systemPrompt = buildSubAgentPrompt(record.task());
                RunTrace trace = traceFactory.start();
                trace.setSessionId(runContext.sessionId());
                trace.recordInput(null, record.task().description(), List.of());
                trace.recordLlmMeta("sub-agent", "sub-agent");

                // Execute with task-specific cancellation token
                ReActResult result = reActLoop.execute(new ReActRequest(
                        systemPrompt,
                        record.task().description(),
                        List.of(),
                        trace,
                        null,
                        taskToken,
                        null,
                        null,
                        finalOutputContract(record.task())));

                long duration = System.currentTimeMillis() - start;
                log.info("[SubAgentManager] Task {} completed in {}ms, steps={}", taskId, duration, result.steps().size());

                // Persist full ReAct steps only in the linked sub-agent trace.
                String subTraceId = saveSubAgentTrace(trace, record, runContext, result, duration);

                SubAgentCompletionContractValidator.Evaluation evaluation =
                        completionContractValidator.evaluate(
                                record.task().completionContract(),
                                result.steps(), result.artifacts(), result.output());
                SubAgentResult subResult;
                if (evaluation.contractValidation().satisfied()) {
                    subResult = SubAgentResult.success(
                            taskId, result.output(), evaluation, duration, subTraceId);
                    record.succeed(subResult);
                } else {
                    subResult = SubAgentResult.incomplete(
                            taskId, result.output(), evaluation, duration, subTraceId);
                    record.markIncomplete(subResult);
                }
                return subResult;

            } catch (Exception e) {
                long duration = System.currentTimeMillis() - start;

                // Check if this was a cancellation
                if (record.isCancelRequested() || taskToken.isCancelled()) {
                    log.info("[SubAgentManager] Task {} cancelled after {}ms", taskId, duration);
                    record.markCancelled();
                    return SubAgentResult.failure(
                            taskId, "Cancelled", duration,
                            record.task().completionContract() != null);
                }

                log.error("[SubAgentManager] Task {} failed in {}ms: {}", taskId, duration, e.getMessage());
                SubAgentResult failResult = SubAgentResult.failure(
                        taskId, e.getMessage(), duration,
                        record.task().completionContract() != null);
                record.fail(failResult);
                return failResult;

            } finally {
                HttpApiTool.clearCurrentCredentials();
                KnowledgeGraphTool.clearCurrentContext();
                KnowledgeAccessService.clearCurrentContext();
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
                                      0, record.task().completionContract() != null);
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
                            error != null ? error.getMessage() : "Task failed", 0,
                            record.task().completionContract() != null));
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

        SubAgentCompletionContract contract = task.completionContract();
        if (contract != null) {
            sb.append("\n[Completion contract]\n");
            if (!contract.requiredSuccessfulTools().isEmpty()) {
                sb.append("Successfully execute each required tool at least once: ")
                        .append(String.join(", ", contract.requiredSuccessfulTools()))
                        .append(".\n");
            }
            for (RequiredArtifact artifact : contract.requiredArtifacts()) {
                sb.append("Produce at least ").append(artifact.minCount())
                        .append(" stored artifact(s) of type ")
                        .append(artifact.artifactType());
                if (!artifact.allowedMimeTypes().isEmpty()) {
                    sb.append(" with MIME type in ")
                            .append(String.join(", ", artifact.allowedMimeTypes()));
                }
                sb.append(".\n");
            }
            if (contract.outputSchema() != null) {
                sb.append("Return the final summary as JSON matching the supplied output schema.\n");
            }
        }

        return sb.toString();
    }

    private static FinalOutputContract finalOutputContract(SubAgentTask task) {
        JsonNode schema = task.completionContract() != null
                ? task.completionContract().outputSchema()
                : null;
        return schema == null
                ? new FinalOutputContract.Text()
                : new FinalOutputContract.JsonSchema(
                        "subAgentCompletion", schema, true);
    }

    /**
     * Persist sub-agent trace to TraceStore.
     * Records input, steps, output, and links to parent via metadata.
     *
     * @return the sub-agent's trace ID, or null if persistence failed
     */
    private String saveSubAgentTrace(RunTrace trace, SubAgentTaskRecord record,
                                     AgentRunContext runContext, ReActResult result, long durationMs) {
        try {
            result.steps().forEach(trace::addStep);
            RiskLevel risk = result.steps().stream()
                    .flatMap(step -> step.toolResults().stream())
                    .anyMatch(toolResult -> !toolResult.success())
                    ? RiskLevel.MEDIUM : RiskLevel.LOW;
            trace.recordOutput(result.output(), risk, true);

            // Link to parent trace via metadata
            java.util.Map<String, String> meta = new java.util.HashMap<>();
            meta.put("sub_agent_task_id", record.taskId());
            meta.put("parent_run_id", runContext.runId());
            if (runContext.parentTraceId() != null) {
                meta.put("parent_trace_id", runContext.parentTraceId());
            }
            meta.put("sub_agent_persona", record.task().persona() != null ? record.task().persona() : "");
            meta.put("sub_agent_tools", String.join(",", record.task().tools()));
            meta.put("sub_agent_duration_ms", String.valueOf(durationMs));
            trace.putMetadata(meta);

            var subTrace = trace.finish();
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
                                        record.taskId(), "Task timed out (scope TTL expired)", 0,
                                        record.task().completionContract() != null);
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

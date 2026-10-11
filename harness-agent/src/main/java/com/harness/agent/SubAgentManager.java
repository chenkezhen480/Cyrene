package com.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.context.KnowledgeAccessService;
import com.harness.agent.knowledge.KnowledgeToolRuntimeContext;
import com.harness.core.model.ArtifactStore;
import com.harness.react.ReActLoop;
import com.harness.react.ReActLoopFactory;
import com.harness.react.ReActRequest;
import com.harness.react.ReActResult;
import com.harness.core.model.CancellationToken;
import com.harness.core.model.FinalOutputContract;
import com.harness.core.model.SubAgentLifecycleEvent;
import com.harness.core.model.RiskLevel;
import com.harness.core.runtime.RunTrace;
import com.harness.core.runtime.RunTraceFactory;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.model.PageResponse;
import com.harness.agent.subagent.SubAgentTaskRepository;
import com.harness.agent.subagent.InMemorySubAgentTaskRepository;
import com.harness.provider.ChatModelProvider;
import com.harness.tool.RunToolCatalog;
import com.harness.tool.HttpApiTool;
import com.harness.tool.ToolExecutor;
import com.harness.tool.builtin.StructuredOutputTool;
import com.harness.tool.web.AuthorizedUrlContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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
    private final SubAgentTaskRepository taskRepository;

    // Configurable limits
    private final int maxConcurrent;
    private final int maxTasksPerRun;
    private final long scopeTtlMinutes;
    private final ChatModelProvider chatModelProvider;

    // Per-run scopes, keyed by runId
    private final ConcurrentHashMap<String, SubAgentRunScope> scopes = new ConcurrentHashMap<>();

    // Global executor shared across all runs
    private volatile ExecutorService executor;

    // Scheduled executor for TTL cleanup
    private final ScheduledExecutorService cleanupScheduler;

    // Counter for active tasks (for monitoring)
    private final AtomicInteger activeTasks = new AtomicInteger(0);

    /** Embedded compatibility only; production must inject its authoritative repository. */
    @Deprecated
    public SubAgentManager(ReActLoopFactory reActLoopFactory,
                           RunTraceFactory traceFactory,
                           ToolExecutor toolExecutor,
                           ArtifactStore artifactStore,
                           SessionInbox sessionInbox,
                           SessionResumeDispatcher resumeDispatcher,
                           ChatModelProvider chatModelProvider) {
        this(reActLoopFactory, traceFactory, toolExecutor, artifactStore, sessionInbox,
                resumeDispatcher, chatModelProvider,
                new InMemorySubAgentTaskRepository(java.time.Duration.ofHours(
                        EnvConfig.get().getLong(EnvKey.AGENT_TASK_RETENTION_HOURS, 168))));
    }

    public SubAgentManager(ReActLoopFactory reActLoopFactory, RunTraceFactory traceFactory,
                           ToolExecutor toolExecutor, ArtifactStore artifactStore,
                           SessionInbox sessionInbox, SessionResumeDispatcher resumeDispatcher,
                           ChatModelProvider chatModelProvider, SubAgentTaskRepository taskRepository) {
        this.reActLoopFactory = java.util.Objects.requireNonNull(reActLoopFactory, "reActLoopFactory");
        this.traceFactory = java.util.Objects.requireNonNull(traceFactory, "traceFactory");
        this.toolExecutor = toolExecutor;
        this.sessionInbox = sessionInbox;
        this.resumeDispatcher = resumeDispatcher;
        this.taskRepository = java.util.Objects.requireNonNull(taskRepository, "taskRepository");
        this.chatModelProvider = java.util.Objects.requireNonNull(
                chatModelProvider, "chatModelProvider");
        this.completionContractValidator = new SubAgentCompletionContractValidator(
                artifactStore, new ObjectMapper());

        // Load configurable limits from env
        this.maxConcurrent = EnvConfig.get().getInt(EnvKey.AGENT_MAX_SUBAGENTS, 3);
        this.maxTasksPerRun = EnvConfig.get().getInt(EnvKey.AGENT_MAX_TASKS_PER_RUN, 16);
        this.scopeTtlMinutes = EnvConfig.get().getLong(EnvKey.AGENT_SCOPE_TTL_MINUTES, 30);
        long recoverySeconds = EnvConfig.get().getLong(EnvKey.AGENT_DELIVERY_LEASE_SECONDS, 60);
        if (recoverySeconds < 1) throw new IllegalArgumentException("Delivery lease seconds must be positive");
        while (taskRepository.interruptUnfinished(100) == 100) { }

        // Schedule TTL cleanup every 5 minutes
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "subagent-scope-cleanup");
            t.setDaemon(true);
            return t;
        });
        this.cleanupScheduler.scheduleAtFixedRate(() -> {
            try { cleanupExpiredScopes(); }
            catch (RuntimeException e) { log.error("Sub-agent scope cleanup failed", e); }
        }, 5, 5, TimeUnit.MINUTES);
        this.cleanupScheduler.scheduleWithFixedDelay(() -> {
            try {
                taskRepository.deleteExpired(100);
                recoverPendingDeliveries();
            } catch (RuntimeException e) { log.error("Sub-agent recovery failed", e); }
        }, recoverySeconds, recoverySeconds, TimeUnit.SECONDS);

        log.info("[SubAgentManager] Initialized: maxConcurrent={}, maxTasksPerRun={}, scopeTtlMinutes={}",
                maxConcurrent, maxTasksPerRun, scopeTtlMinutes);
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
        return openScope(runId, event -> { });
    }

    public SubAgentRunScope openScope(
            String runId,
            Consumer<SubAgentLifecycleEvent> lifecycleListener
    ) {
        return openScope(runId, null, lifecycleListener);
    }

    public SubAgentRunScope openScope(String runId, String sessionId,
                                     Consumer<SubAgentLifecycleEvent> lifecycleListener) {
        SubAgentRunScope scope = new SubAgentRunScope(
                runId, sessionId, maxTasksPerRun, lifecycleListener);
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
        for (var record : scope.taskRecords()) {
            try { finishOwnerTask(record); }
            catch (RuntimeException e) { log.error("Sub-agent delivery persistence pending for " + record.taskId(), e); }
        }
        cleanupIfDone(runId);
        if (scopes.containsKey(runId)) {
            int running = scope.getTasksByStatus(SubAgentStatus.RUNNING).size();
            int queued = scope.getTasksByStatus(SubAgentStatus.QUEUED).size();
            log.info("[SubAgentManager] Scope {} has {} running/{} queued tasks, will cleanup on TTL", runId, running, queued);
        }
    }

    private void finishOwnerTask(SubAgentTaskRecord record) {
        if (parentCancelled(record)) {
            record.suppressDelivery();
            record.requestCancel();
        }
        else detachTask(record);
    }

    /** Subscribe after claiming delivery; already completed futures deliver immediately. */
    public void detachTask(SubAgentTaskRecord record) {
        if (record.ownerSessionId() == null || parentCancelled(record)) return;
        if (record.detach()) {
            record.completion().thenAccept(result -> submitCompletionEvent(record, result));
        } else if (record.isDetached() && record.completion().isDone() && !record.completion().isCompletedExceptionally()) {
            submitCompletionEvent(record, record.completion().join());
        }
    }

    private static boolean parentCancelled(SubAgentTaskRecord record) {
        CancellationToken token = record.taskCancellationToken();
        return token != null && token.getParent() != null && token.getParent().isCancelled();
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

    public boolean hasDetachedTasks(String runId) {
        SubAgentRunScope scope = scopes.get(runId);
        return scope != null && scope.getAllTasks().values().stream()
                .filter(record -> record.ownerSessionId() != null && !parentCancelled(record))
                .map(this::deliveryState)
                .anyMatch(state -> state != ResultDeliveryState.INLINE_CONSUMED
                        && state != ResultDeliveryState.SESSION_RESUMED && state != ResultDeliveryState.SUPPRESSED);
    }

    /**
     * Generate a unique task ID.
     */
    public static String generateTaskId() {
        return "sub-" + UUID.randomUUID();
    }

    public SubAgentTaskRecord findTask(String runId, String toolCallId) {
        SubAgentRunScope scope = scopes.get(runId);
        return scope == null ? null : scope.getAllTasks().values().stream()
                .filter(record -> java.util.Objects.equals(toolCallId, record.spawnToolCallId())).findFirst().orElse(null);
    }

    public List<String> pendingTaskIds(String runId) {
        SubAgentRunScope scope = scopes.get(runId);
        return scope == null ? List.of() : scope.getAllTasks().values().stream()
                .filter(record -> {
                    ResultDeliveryState delivery = deliveryState(record);
                    return delivery != ResultDeliveryState.INLINE_CONSUMED && delivery != ResultDeliveryState.SESSION_RESUMED
                            && delivery != ResultDeliveryState.SUPPRESSED;
                }).map(SubAgentTaskRecord::taskId).sorted().toList();
    }

    private ResultDeliveryState deliveryState(SubAgentTaskRecord record) {
        if (record.owner() == null || record.ownerSessionId() == null) return record.deliveryState().get();
        return taskRepository.findAuthorized(record.owner(), record.ownerSessionId(), record.taskId())
                .map(SubAgentTaskRepository.StoredTask::deliveryState).orElse(record.deliveryState().get());
    }

    public PageResponse<SubAgentTaskRepository.StoredTask> listTasks(
            AgentRunContext.Owner owner, String sessionId, String cursor, int limit) {
        return taskRepository.listAuthorized(owner, sessionId, cursor, limit);
    }

    public List<SubAgentTaskRepository.StoredTask> findTasks(
            AgentRunContext.Owner owner, String sessionId, List<String> taskIds) {
        if (taskIds == null || taskIds.isEmpty() || taskIds.size() > 100) throw new IllegalArgumentException("Provide between 1 and 100 task IDs");
        return taskIds.stream().distinct().map(id -> taskRepository.findAuthorized(owner, sessionId, id)
                .orElseThrow(() -> new IllegalArgumentException("Task not found in authorized session: " + id))).toList();
    }

    public boolean cancelSession(String sessionId) {
        var records = scopes.values().stream().flatMap(scope -> scope.getAllTasks().values().stream())
                .filter(record -> java.util.Objects.equals(sessionId, record.ownerSessionId())).toList();
        boolean live = records.stream().anyMatch(record -> !record.isTerminal());
        records.forEach(record -> {
            if (record.taskCancellationToken() != null) record.taskCancellationToken().cancel();
        });
        RuntimeException failure = null;
        boolean resumed = false;
        boolean suppressed = false;
        try { resumed = resumeDispatcher.cancelSession(sessionId); }
        catch (RuntimeException e) { failure = e; }
        try { suppressed = taskRepository.suppressSession(sessionId) > 0; }
        catch (RuntimeException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        for (var record : records) {
            try { record.requestCancel(); }
            catch (RuntimeException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        if (failure != null) throw failure;
        return live || resumed || suppressed;
    }

    public void registerResumeToken(String sessionId, CancellationToken token) { resumeDispatcher.registerResumeToken(sessionId, token); }
    public void unregisterResumeToken(String sessionId, CancellationToken token) { resumeDispatcher.unregisterResumeToken(sessionId, token); }

    public void recoverPendingDeliveries() {
        resumeDispatcher.retryPendingSuppressions();
        for (var scope : scopes.values()) {
            for (var record : scope.taskRecords()) {
                try {
                    if (record.pendingTerminalResult() != null) record.retryPendingCompletion();
                    if (scope.state() == SubAgentRunScope.ScopeState.OWNER_FINISHED) finishOwnerTask(record);
                } catch (RuntimeException e) { log.error("Sub-agent completion persistence pending for " + record.taskId(), e); }
            }
            cleanupIfDone(scope.runId());
        }
        String cursor = "";
        while (true) {
            var page = taskRepository.pendingSessions(cursor, 100);
            page.items().stream().filter(sessionId -> !hasOpenSessionScope(sessionId))
                    .forEach(resumeDispatcher::requestResume);
            if (!page.pageInfo().hasMore()) return;
            cursor = page.pageInfo().nextCursor();
        }
    }

    private boolean hasOpenSessionScope(String sessionId) {
        return scopes.values().stream().anyMatch(scope -> scope.state() == SubAgentRunScope.ScopeState.OPEN
                && (sessionId.equals(scope.sessionId()) || scope.sessionId() == null
                && scope.taskRecords().stream().anyMatch(record -> sessionId.equals(record.ownerSessionId()))));
    }

    /**
     * Submit a task for async execution. Returns immediately without blocking.
     * Task is registered in the scope before execution begins.
     *
     * @return the task record, or null if submission failed
     */
    public SubAgentTaskRecord submitTask(AgentRunContext runContext, SubAgentTask task, String sessionId) {
        return submitTask(runContext, task, sessionId, null);
    }

    public SubAgentTaskRecord submitTask(
            AgentRunContext runContext,
            SubAgentTask task,
            String sessionId,
            String toolCallId
    ) {
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
        if (parentToken.isCancelled()) throw new CancellationException("Request cancelled");
        if (sessionId != null) resumeDispatcher.allowSession(sessionId);

        // Register task in scope
        SubAgentTaskRecord record = scope.registerTask(
                task, taskToken, sessionId, runContext.turnId(), runContext.owner(),
                toolCallId, runContext.parentTraceId(), taskRepository);
        if (record == null) {
            return null;  // Scope not open, spawn limit reached, or duplicate
        }
        record.completion().thenAccept(result -> {
            publishTerminal(scope, toolCallId, record, result);
            cleanupIfDone(runId);
        });

        // Execute async
        executeTask(runContext, scope, record, toolCallId);

        return record;
    }

    /**
     * Execute a task asynchronously with timeout.
     */
    private void executeTask(
            AgentRunContext runContext,
            SubAgentRunScope scope,
            SubAgentTaskRecord record,
            String toolCallId
    ) {
        // Capture credentials from parent thread
        final Map<String, String> parentCredentials = HttpApiTool.getCurrentCredentialsSnapshot();
        final KnowledgeGraphTool.ContextSnapshot graphContext =
                KnowledgeGraphTool.captureCurrentContext();
        final KnowledgeAccessService.ContextSnapshot knowledgeContext =
                KnowledgeAccessService.captureCurrentContext();
        final KnowledgeToolRuntimeContext unifiedKnowledgeContext =
                KnowledgeToolRuntimeContext.captureCurrent();
        final Set<String> parentAuthorizedUrls = AuthorizedUrlContext.snapshot();

        CompletableFuture<SubAgentResult> future = CompletableFuture.supplyAsync(() -> {
            Thread currentThread = Thread.currentThread();
            CancellationToken taskToken = record.taskCancellationToken();

            // Register with task cancellation token (not parent)
            taskToken.trackThread(currentThread);

            // Propagate credentials to sub-agent thread
            HttpApiTool.setCurrentCredentials(parentCredentials);
            KnowledgeGraphTool.restoreCurrentContext(graphContext);
            KnowledgeAccessService.restoreCurrentContext(knowledgeContext);
            // 子 agent 在独立线程池执行，ThreadLocal 不会跨线程传递，必须显式继承父运行的
            // URL 授权集；否则子 agent 的所有 URL 工具都会 fail-closed。
            AuthorizedUrlContext.set(parentAuthorizedUrls);

            long start = System.currentTimeMillis();
            String taskId = record.taskId();
            activeTasks.incrementAndGet();

            try {
                if (!record.start()) return record.storedResult();
                publishLifecycle(scope, toolCallId, record,
                        SubAgentLifecycleEvent.Status.RUNNING, "");

                FinalOutputContract outputContract = finalOutputContract(record.task());
                RunToolCatalog subAgentToolCatalog =
                        runContext.toolCatalog().allowing(record.task().tools());
                if (outputContract instanceof FinalOutputContract.JsonSchema jsonSchema
                        && subAgentToolCatalog.contains(StructuredOutputTool.TOOL_NAME)) {
                    subAgentToolCatalog = subAgentToolCatalog.replacing(
                            StructuredOutputTool.terminal(jsonSchema));
                }
                // The task's own trace must exist before the knowledge context is restored: the
                // restored context is what a knowledge_search inside this task records into, and
                // pointing it at the parent trace would overwrite the parent's own search.
                RunTrace trace = traceFactory.start();
                trace.setSessionId(runContext.sessionId());
                trace.recordInput(record.owner() == null ? null : record.owner().userId(),
                        record.task().description(), List.of());
                if (record.owner() != null) {
                    if (record.owner().tenantId() != null) trace.putMetadata("tenant_id", record.owner().tenantId());
                    trace.putMetadata("identity", record.owner().identity());
                }
                trace.recordLlmMeta("sub-agent", "sub-agent");
                SpawnSubAgentTool.setCurrentRunContext(new AgentRunContext(
                        runContext.runId(), runContext.sessionId(), taskToken, trace.traceId(),
                        subAgentToolCatalog, runContext.turnId(), runContext.owner(), record.taskId()));
                KnowledgeToolRuntimeContext.restoreForCatalog(
                        unifiedKnowledgeContext, subAgentToolCatalog, trace);

                ReActLoop reActLoop = reActLoopFactory.create(subAgentToolCatalog, toolExecutor);

                // Build system prompt from LLM-generated persona + systemPrompt + context
                String systemPrompt = buildSubAgentPrompt(record.task());

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
                        outputContract));

                long duration = System.currentTimeMillis() - start;
                log.info("[SubAgentManager] Task {} completed in {}ms, steps={}", taskId, duration, result.steps().size());

                SubAgentCompletionContractValidator.Evaluation evaluation =
                        completionContractValidator.evaluate(
                                record.task().completionContract(),
                                result.steps(), result.artifacts(), result.output(), subAgentToolCatalog);
                // Persist full ReAct steps and evaluated completion evidence together.
                String subTraceId = saveSubAgentTrace(
                        trace, record, runContext, result, evaluation, duration);
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
                return record.storedResult();

            } catch (Exception e) {
                long duration = System.currentTimeMillis() - start;
                if (record.pendingTerminalResult() != null) {
                    log.error("Sub-agent completion persistence pending for " + record.taskId(), e);
                    return record.pendingTerminalResult();
                }

                // Check if this was a cancellation
                if (record.isCancelRequested() || taskToken.isCancelled()) {
                    log.info("[SubAgentManager] Task {} cancelled after {}ms", taskId, duration);
                    record.markCancelled();
                    return record.storedResult();
                }

                log.error("[SubAgentManager] Task {} failed in {}ms: {}", taskId, duration, e.getMessage());
                SubAgentResult failResult = SubAgentResult.failure(
                        taskId, e.getMessage(), duration,
                        record.task().completionContract() != null);
                record.fail(failResult);
                return record.storedResult();

            } finally {
                SpawnSubAgentTool.clearCurrentRunContext();
                HttpApiTool.clearCurrentCredentials();
                KnowledgeGraphTool.clearCurrentContext();
                KnowledgeAccessService.clearCurrentContext();
                KnowledgeToolRuntimeContext.clear();
                AuthorizedUrlContext.clear();
                taskToken.untrackThread(currentThread);
                activeTasks.decrementAndGet();
                Thread.interrupted();

                // If scope is owner-finished and all tasks terminal, clean up
                cleanupIfDone(runContext.runId());
            }
        }, getOrCreateExecutor());

        // Apply task-level timeout
        long taskTimeoutSeconds = chatModelProvider.timeoutSeconds();
        if (taskTimeoutSeconds > 0) {
            future.orTimeout(taskTimeoutSeconds, TimeUnit.SECONDS)
                  .exceptionally(ex -> {
                      if (ex instanceof TimeoutException) {
                          log.warn("[SubAgentManager] Task {} timed out after {}s", record.taskId(), taskTimeoutSeconds);
                          record.taskCancellationToken().cancel();
                          try { record.markTimedOut(); }
                          catch (RuntimeException e) { log.error("Sub-agent timeout persistence pending for " + record.taskId(), e); }
                      }
                      return null;
                  });
        }

        future.whenComplete((result, error) -> {
            if (error != null && !(error instanceof TimeoutException) && record.pendingTerminalResult() == null) {
                try {
                    record.fail(SubAgentResult.failure(record.taskId(), error.getMessage(), 0,
                            record.task().completionContract() != null));
                } catch (RuntimeException e) { log.error("Sub-agent completion persistence pending for " + record.taskId(), e); }
            }
        });
    }

    public void publishLifecycle(String runId, SubAgentLifecycleEvent event) {
        SubAgentRunScope scope = scopes.get(runId);
        if (scope != null) {
            scope.publish(event);
        }
    }

    private static void publishLifecycle(
            SubAgentRunScope scope,
            String toolCallId,
            SubAgentTaskRecord record,
            SubAgentLifecycleEvent.Status status,
            String detail
    ) {
        if (toolCallId == null || toolCallId.isBlank()) {
            return;
        }
        scope.publish(new SubAgentLifecycleEvent(
                toolCallId, record.taskId(), status, detail));
    }

    private static void publishTerminal(
            SubAgentRunScope scope,
            String toolCallId,
            SubAgentTaskRecord record,
            SubAgentResult result
    ) {
        if (toolCallId == null || toolCallId.isBlank()
                || !record.markLifecycleTerminalPublished()) {
            return;
        }
        SubAgentLifecycleEvent.Status status = switch (record.status().get()) {
            case SUCCEEDED -> SubAgentLifecycleEvent.Status.COMPLETED;
            case CANCELLED -> SubAgentLifecycleEvent.Status.CANCELLED;
            case TIMED_OUT -> SubAgentLifecycleEvent.Status.TIMED_OUT;
            case INCOMPLETE, FAILED -> SubAgentLifecycleEvent.Status.FAILED;
            default -> throw new IllegalStateException(
                    "Sub-agent terminal event requires terminal task status");
        };
        String detail = result != null && result.error() != null
                ? result.error()
                : status == SubAgentLifecycleEvent.Status.FAILED
                        ? "Sub-agent did not complete successfully"
                        : "";
        publishLifecycle(scope, toolCallId, record, status, detail);
    }

    /**
     * Submit a completion event to the session inbox and trigger resume.
     * Durable inboxes claim the already registered event; legacy inboxes claim it in memory.
     */
    private void submitCompletionEvent(SubAgentTaskRecord record, SubAgentResult result) {
        if (parentCancelled(record)) return;
        SubAgentRunScope scope = scopes.get(record.ownerRunId());
        if (scope != null && scope.state() == SubAgentRunScope.ScopeState.OPEN) return;
        if (sessionInbox.isPersistent() && deliveryState(record) != ResultDeliveryState.DETACHED) return;
        if (!record.markCompletionEventRequested()) return;
        // Durable state is acknowledged only after the callback writes the session messages.
        if (!sessionInbox.isPersistent() && !record.markSessionResumed()) {
            log.debug("[SubAgentManager] Task {} already session-resumed, skipping duplicate event", record.taskId());
            return;
        }

        String sessionId = record.ownerSessionId();
        String eventId = "subagent:" + record.taskId();

        SessionInbox.SubAgentCompletedEvent event = new SessionInbox.SubAgentCompletedEvent(
                eventId,
                sessionId,
                record.taskId(),
                record.task().description(),
                record.ownerTurnId(),
                result,
                java.time.Instant.now(),
                SessionInbox.SubAgentCompletedEvent.EventStatus.PENDING,
                record.owner()
        );

        sessionInbox.submit(event);
        log.debug("[SubAgentManager] Completion event submitted: sessionId={}, taskId={}", sessionId, record.taskId());

        // Trigger session resume
        if (!sessionInbox.isPersistent() || !hasOpenSessionScope(sessionId)) resumeDispatcher.requestResume(sessionId);
    }

    /**
     * Clean up scope if owner is finished and all tasks are terminal.
     */
    private void cleanupIfDone(String runId) {
        SubAgentRunScope scope = scopes.get(runId);
        if (scope != null && scope.state() == SubAgentRunScope.ScopeState.OWNER_FINISHED && scope.allTasksTerminal()) {
            try {
                if (scope.taskRecords().stream().anyMatch(record -> {
                    ResultDeliveryState delivery = deliveryState(record);
                    return record.ownerSessionId() != null && (delivery == ResultDeliveryState.INLINE_PENDING
                            || parentCancelled(record) && delivery != ResultDeliveryState.SUPPRESSED
                            && delivery != ResultDeliveryState.INLINE_CONSUMED && delivery != ResultDeliveryState.SESSION_RESUMED);
                })) return;
            } catch (RuntimeException e) {
                log.error("Sub-agent delivery state pending for run " + runId, e);
                return;
            }
            scopes.remove(runId, scope);
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
    private String saveSubAgentTrace(
            RunTrace trace,
            SubAgentTaskRecord record,
            AgentRunContext runContext,
            ReActResult result,
            SubAgentCompletionContractValidator.Evaluation evaluation,
            long durationMs
    ) {
        try {
            result.steps().forEach(trace::addStep);
            RiskLevel risk = result.steps().stream()
                    .flatMap(step -> step.toolResults().stream())
                    .anyMatch(toolResult -> !toolResult.success())
                    ? RiskLevel.MEDIUM : RiskLevel.LOW;
            trace.recordOutput(result.output(), risk, true);
            if (result.loopStats() != null) {
                var stats = result.loopStats();
                trace.recordReactStats(
                        stats.outcome(), stats.rounds(), stats.toolCalls(),
                        stats.reflectionChecks(), stats.inputTokens(), stats.outputTokens(),
                        stats.llmCalls(), stats.toolRetries());
            }

            // Link to parent trace via metadata
            java.util.Map<String, String> meta = new java.util.HashMap<>();
            meta.put("sub_agent_task_id", record.taskId());
            meta.put("run_id", record.taskId());
            meta.put("parent_run_id", runContext.runId());
            if (runContext.parentTraceId() != null) {
                meta.put("parent_trace_id", runContext.parentTraceId());
            }
            meta.put("sub_agent_persona", record.task().persona() != null ? record.task().persona() : "");
            meta.put("sub_agent_tools", String.join(",", record.task().tools()));
            meta.put("sub_agent_duration_ms", String.valueOf(durationMs));
            meta.put("completion_validated", String.valueOf(
                    evaluation.contractValidation().satisfied()));
            meta.put("task_contract_status",
                    evaluation.contractValidation().status().name().toLowerCase(java.util.Locale.ROOT));
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

        for (Map.Entry<String, SubAgentRunScope> entry : scopes.entrySet()) {
            SubAgentRunScope scope = entry.getValue();
            if (scope.state() == SubAgentRunScope.ScopeState.OWNER_FINISHED) {
                long elapsedMinutes = java.time.Duration.between(scope.lastAccessedAt(), now).toMinutes();
                if (elapsedMinutes >= scopeTtlMinutes) {
                    // Cancel remaining tasks and submit timeout events
                    for (SubAgentTaskRecord record : scope.taskRecords()) {
                        try {
                            if (!record.isTerminal()) {
                                if (record.pendingTerminalResult() != null) record.retryPendingCompletion();
                                else {
                                    record.taskCancellationToken().cancel();
                                    record.markTimedOut();
                                }
                            }
                            finishOwnerTask(record);
                        } catch (RuntimeException e) { log.error("Sub-agent scope cleanup pending for " + record.taskId(), e); }
                    }
                    cleanupIfDone(entry.getKey());
                }
            }
        }

    }
}

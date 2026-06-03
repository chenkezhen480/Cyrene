package com.harness.agent;

import com.harness.ai.model.ChatModelProvider;
import com.harness.ai.model.VisionModelProvider;
import com.harness.ai.model.VoiceModelProvider;
import com.harness.ai.react.ReActEngine;
import com.harness.core.model.AgentTrace;
import com.harness.core.model.CancellationToken;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.tool.ToolExecutor;
import com.harness.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Manages sub-agent lifecycle: task submission, dependency resolution, parallel execution.
 * Each sub-agent gets its own ReActEngine instance but shares the ToolRegistry.
 */
public class SubAgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SubAgentOrchestrator.class);

    private final ChatModelProvider chatModelProvider;
    private final VisionModelProvider visionModelProvider;
    private final VoiceModelProvider voiceModelProvider;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final int maxConcurrent;

    private final ExecutorService executor;
    private final Map<String, CompletableFuture<SubAgentResult>> submittedTasks = new ConcurrentHashMap<>();

    // Parent cancellation token, set per-run by AgentOrchestrator
    private volatile CancellationToken parentToken;

    public SubAgentOrchestrator(ChatModelProvider chatModelProvider,
                                 VisionModelProvider visionModelProvider,
                                 VoiceModelProvider voiceModelProvider,
                                 ToolRegistry toolRegistry,
                                 ToolExecutor toolExecutor) {
        this.chatModelProvider = chatModelProvider;
        this.visionModelProvider = visionModelProvider;
        this.voiceModelProvider = voiceModelProvider;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.maxConcurrent = EnvConfig.get().getInt(EnvKey.AGENT_MAX_SUBAGENTS, 3);
        this.executor = Executors.newFixedThreadPool(maxConcurrent, r -> {
            Thread t = new Thread(r, "sub-agent-worker");
            t.setDaemon(true);
            return t;
        });
        log.info("[SubAgent] Orchestrator initialized: maxConcurrent={}", maxConcurrent);
    }

    /**
     * Set the parent cancellation token. Called by AgentOrchestrator before each run.
     */
    public void setParentToken(CancellationToken token) {
        this.parentToken = token;
    }

    /**
     * Submit a single sub-agent task. Returns a future that completes when the task finishes.
     */
    public CompletableFuture<SubAgentResult> submitTask(SubAgentTask task) {
        log.info("[SubAgent] Submitting task: id={}, deps={}", task.taskId(), task.dependencies());

        // Check parent cancellation before submitting
        if (parentToken != null && parentToken.isCancelled()) {
            log.info("[SubAgent] Parent cancelled, skipping task: id={}", task.taskId());
            return CompletableFuture.completedFuture(
                    SubAgentResult.failure(task.taskId(), "Cancelled by parent", List.of(), 0));
        }

        if (task.dependencies().isEmpty()) {
            // No dependencies: execute immediately
            return executeTask(task);
        }

        // Has dependencies: wait for all dependencies to complete first
        List<CompletableFuture<SubAgentResult>> depFutures = task.dependencies().stream()
                .map(depId -> submittedTasks.getOrDefault(depId,
                        CompletableFuture.completedFuture(
                                SubAgentResult.failure(depId, "Dependency not found: " + depId, List.of(), 0))))
                .toList();

        CompletableFuture<Void> allDeps = CompletableFuture.allOf(
                depFutures.toArray(new CompletableFuture[0]));

        return allDeps.thenCompose(v -> {
            // Check if any dependency failed
            for (CompletableFuture<SubAgentResult> depFuture : depFutures) {
                SubAgentResult depResult = depFuture.join();
                if (!depResult.success()) {
                    log.warn("[SubAgent] Dependency {} failed, skipping task {}", depResult.taskId(), task.taskId());
                    return CompletableFuture.completedFuture(
                            SubAgentResult.failure(task.taskId(),
                                    "Dependency " + depResult.taskId() + " failed: " + depResult.output(),
                                    List.of(), 0));
                }
            }
            return executeTask(task);
        });
    }

    /**
     * Submit multiple tasks. Independent tasks run in parallel; dependent tasks wait for dependencies.
     * Returns a map of taskId -> future result.
     */
    public Map<String, CompletableFuture<SubAgentResult>> submitTasks(List<SubAgentTask> tasks) {
        log.info("[SubAgent] Submitting {} tasks", tasks.size());
        for (SubAgentTask task : tasks) {
            CompletableFuture<SubAgentResult> future = submitTask(task);
            submittedTasks.put(task.taskId(), future);
        }
        return Map.copyOf(submittedTasks);
    }

    /**
     * Wait for all submitted tasks to complete and return their results.
     */
    public Map<String, SubAgentResult> awaitAll(long timeoutSeconds) {
        try {
            CompletableFuture.allOf(submittedTasks.values().toArray(new CompletableFuture[0]))
                    .get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("[SubAgent] Timeout waiting for tasks to complete");
        } catch (Exception e) {
            log.error("[SubAgent] Error waiting for tasks: {}", e.getMessage());
        }
        return submittedTasks.entrySet().stream()
                .filter(e -> e.getValue().isDone())
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().join()));
    }

    /**
     * Execute a single sub-agent task using a fresh ReActEngine instance.
     * Propagates parent cancellation token to the sub-agent.
     */
    private CompletableFuture<SubAgentResult> executeTask(SubAgentTask task) {
        return CompletableFuture.supplyAsync(() -> {
            Thread currentThread = Thread.currentThread();
            // Register with parent token so cancel() interrupts sub-agent threads too
            if (parentToken != null) {
                parentToken.trackThread(currentThread);
            }
            long start = System.currentTimeMillis();
            log.info("[SubAgent] Executing task: id={}", task.taskId());
            try {
                // Each sub-agent gets its own ReActEngine with shared tools
                ReActEngine engine = new ReActEngine(chatModelProvider, toolRegistry, toolExecutor,
                        visionModelProvider, voiceModelProvider);

                String systemPrompt = buildSubAgentPrompt(task);
                AgentTrace.Builder traceBuilder = AgentTrace.builder();

                // Pass parent cancellation token to sub-agent
                ReActEngine.ReActResult result = engine.execute(systemPrompt, task.description(),
                        List.of(), traceBuilder, null, parentToken);
                long duration = System.currentTimeMillis() - start;
                log.info("[SubAgent] Task {} completed in {}ms, steps={}", task.taskId(), duration, result.steps().size());
                return SubAgentResult.success(task.taskId(), result.output(), result.steps(), duration);
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - start;
                if (parentToken != null && parentToken.isCancelled()) {
                    log.info("[SubAgent] Task {} cancelled after {}ms", task.taskId(), duration);
                    return SubAgentResult.failure(task.taskId(), "Cancelled", List.of(), duration);
                }
                log.error("[SubAgent] Task {} failed in {}ms: {}", task.taskId(), duration, e.getMessage());
                return SubAgentResult.failure(task.taskId(), e.getMessage(), List.of(), duration);
            } finally {
                if (parentToken != null) {
                    parentToken.untrackThread(currentThread);
                }
            }
        }, executor);
    }

    /**
     * Build a focused system prompt for a sub-agent with only the relevant context.
     */
    private String buildSubAgentPrompt(SubAgentTask task) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a focused sub-agent executing a specific task. ");
        sb.append("Complete the task efficiently using available tools. ");
        sb.append("Return a clear, concise result.\n\n");

        if (task.context() != null && !task.context().isBlank()) {
            sb.append("[Context]\n").append(task.context()).append("\n\n");
        }

        sb.append("[Task]\n").append(task.description()).append("\n");
        return sb.toString();
    }

    /**
     * Shutdown the executor service.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("[SubAgent] Orchestrator shut down");
    }
}

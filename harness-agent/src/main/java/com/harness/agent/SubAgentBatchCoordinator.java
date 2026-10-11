package com.harness.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.model.CancellationToken;
import com.harness.core.model.ResultStatus;
import com.harness.core.model.ToolCall;
import com.harness.core.model.ToolResult;
import com.harness.core.runtime.ToolBatchCoordinator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Waits for all submitted children under one deadline and preserves the original call IDs. */
public final class SubAgentBatchCoordinator implements ToolBatchCoordinator {
    private final SubAgentManager manager;
    private final String runId;
    private final Duration timeout;
    private final boolean autoWait;
    private final ObjectMapper mapper = new ObjectMapper();

    public SubAgentBatchCoordinator(SubAgentManager manager, String runId) {
        this(manager, runId, Duration.ofSeconds(EnvConfig.get().getInt(
                EnvKey.AGENT_AWAIT_TIMEOUT_SECONDS, 120)),
                EnvConfig.get().getBool(EnvKey.AGENT_AUTO_WAIT, true));
    }

    SubAgentBatchCoordinator(SubAgentManager manager, String runId, Duration timeout, boolean autoWait) {
        if (timeout.isNegative()) throw new IllegalArgumentException("Sub-agent timeout cannot be negative");
        this.manager = manager;
        this.runId = runId;
        this.timeout = timeout;
        this.autoWait = autoWait;
    }

    @Override
    public List<ToolResult> coordinate(List<ToolCall> calls, List<ToolResult> results,
                                      CancellationToken cancellationToken) {
        List<SubAgentTaskRecord> records = calls.stream()
                .map(call -> manager.findTask(runId, call.id())).toList();
        List<SubAgentTaskRecord> children = records.stream().filter(java.util.Objects::nonNull).toList();
        long started = System.nanoTime();
        if (autoWait) await(children, cancellationToken, started + timeout.toNanos());
        checkCancellation(cancellationToken);
        List<ToolResult> coordinated = new ArrayList<>(results);
        for (int i = 0; i < records.size(); i++) {
            SubAgentTaskRecord record = records.get(i);
            if (record == null || !results.get(i).success()) continue;
            var payload = mapper.createObjectNode().put("task_id", record.taskId());
            List<SubAgentResult> delivered = List.of();
            ResultStatus status;
            if (autoWait && record.completion().isDone() && record.consumeInline()) {
                SubAgentResult result = record.completion().join();
                SubAgentToolHelper.serializeResult(payload, result, mapper);
                delivered = List.of(result);
                status = ResultStatus.AVAILABLE;
            } else {
                manager.detachTask(record);
                payload.put("status", "PENDING");
                payload.put("task_status", record.status().get().name());
                payload.put("delivery", "RESUME_SESSION");
                status = ResultStatus.PENDING;
            }
            try {
                ToolResult original = results.get(i);
                coordinated.set(i, ToolResult.ok(original.toolCallId(), original.toolName(),
                        SubAgentToolHelper.output(payload, delivered, mapper),
                        original.durationMs() + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), status));
            } catch (JsonProcessingException failure) {
                throw new IllegalStateException("Cannot serialize sub-agent result", failure);
            }
        }
        return List.copyOf(coordinated);
    }

    private static void await(List<SubAgentTaskRecord> children, CancellationToken token, long deadline) {
        var completed = CompletableFuture.allOf(children.stream().map(SubAgentTaskRecord::completion)
                .toArray(CompletableFuture[]::new));
        while (!completed.isDone()) {
            checkCancellation(token);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return;
            try {
                completed.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
            } catch (TimeoutException waiting) {
                // Poll only the cancellation token; the deadline is shared by the whole batch.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Sub-agent wait interrupted");
            } catch (ExecutionException failed) {
                throw new IllegalStateException("Sub-agent completion failed", failed.getCause());
            }
        }
    }

    private static void checkCancellation(CancellationToken token) {
        if (token != null && token.isCancelled()) throw new CancellationException("Request cancelled");
    }
}

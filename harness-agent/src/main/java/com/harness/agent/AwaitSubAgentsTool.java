package com.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ResultStatus;
import com.harness.core.model.ToolExecutionOutcome;
import com.harness.core.model.ToolSpec;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tool to wait for sub-agent tasks to complete and retrieve their results.
 *
 * Key design:
 * - All tasks share a single deadline (default 120s, configurable via HARNESS_AGENT_AWAIT_TIMEOUT_SECONDS)
 * - Completed tasks are consumed inline in the current run
 * - On timeout, uncompleted tasks are detached → auto resume session
 * - Delivery state transitions are CAS-based to prevent duplicate delivery
 *
 * on_timeout modes:
 * - RESUME_SESSION: detach remaining tasks, they will auto-resume session when complete
 * - RETURN_PENDING: just return current status, no auto-resume
 * - CANCEL: cancel remaining tasks
 */
public class AwaitSubAgentsTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(AwaitSubAgentsTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final SubAgentManager subAgentManager;

    public AwaitSubAgentsTool(SubAgentManager subAgentManager) {
        this.subAgentManager = subAgentManager;
    }

    @Override
    public ToolSpec spec() {
        ObjectNode properties = mapper.createObjectNode();

        ObjectNode taskIds = mapper.createObjectNode()
                .put("type", "array")
                .put("description", "List of task IDs to wait for");
        taskIds.set("items", mapper.createObjectNode().put("type", "string"));
        properties.set("task_ids", taskIds);

        ObjectNode returnWhen = mapper.createObjectNode()
                .put("type", "string")
                .put("description", "When to return: ALL (wait for all), ANY (wait for any), FIRST_SUCCESS (wait for first success)");
        returnWhen.set("enum", mapper.createArrayNode().add("ALL").add("ANY").add("FIRST_SUCCESS"));
        properties.set("return_when", returnWhen);

        properties.set("timeout_seconds", mapper.createObjectNode()
                .put("type", "integer")
                .put("description", "Shared timeout for all tasks in seconds (default from env: 120s)"));

        ObjectNode onTimeout = mapper.createObjectNode()
                .put("type", "string")
                .put("description", "Action on timeout: RESUME_SESSION (auto-resume when done), RETURN_PENDING (just return status), CANCEL (cancel remaining)");
        onTimeout.set("enum", mapper.createArrayNode()
                .add("RESUME_SESSION").add("RETURN_PENDING").add("CANCEL"));
        properties.set("on_timeout", onTimeout);

        ObjectNode parameters = mapper.createObjectNode().put("type", "object");
        parameters.set("properties", properties);
        parameters.set("required", mapper.createArrayNode().add("task_ids"));

        return new ToolSpec(
                "await_subagents",
                "Wait for sub-agent tasks to complete and get their results. " +
                        "All tasks share a single timeout deadline. Uncompleted tasks can auto-resume session.",
                parameters,
                com.harness.core.model.ToolCapability.ORCHESTRATION
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        return executeOutcome(arguments).content().modelContent();
    }

    @Override
    public ToolExecutionOutcome executeOutcome(JsonNode arguments) {
        AgentRunContext runContext = SubAgentToolHelper.requireRunContext("await_subagents");

        List<String> taskIds = SubAgentToolHelper.parseTaskIds(arguments);
        if (taskIds.isEmpty()) {
            throw new ToolExecutionException("await_subagents", "Missing required parameter: task_ids");
        }

        String returnWhen = arguments.has("return_when") ? arguments.get("return_when").asText().toUpperCase() : "ALL";
        int defaultTimeout = EnvConfig.get().getInt(EnvKey.AGENT_AWAIT_TIMEOUT_SECONDS, 120);
        int timeoutSeconds = arguments.has("timeout_seconds") ? arguments.get("timeout_seconds").asInt() : defaultTimeout;
        String onTimeout = arguments.has("on_timeout") ? arguments.get("on_timeout").asText().toUpperCase() : "RESUME_SESSION";

        log.info("[AwaitSubAgents] Awaiting tasks: ids={}, returnWhen={}, timeout={}s, onTimeout={}",
                taskIds, returnWhen, timeoutSeconds, onTimeout);

        SubAgentRunScope scope = SubAgentToolHelper.requireScope(subAgentManager, runContext, "await_subagents");

        if (!List.of("ALL", "ANY", "FIRST_SUCCESS").contains(returnWhen)
                || !List.of("RESUME_SESSION", "RETURN_PENDING", "CANCEL").contains(onTimeout)
                || timeoutSeconds < 0) {
            throw new ToolExecutionException("await_subagents", "Invalid wait mode, timeout action or timeout_seconds");
        }
        List<SubAgentTaskRecord> records = SubAgentToolHelper.resolveTaskRecords(
                scope, taskIds, "await_subagents");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        try {
            boolean timedOut = false;
            while (!ready(records, returnWhen)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    timedOut = true;
                    break;
                }
                var futures = records.stream().filter(r -> !r.completion().isDone())
                        .map(SubAgentTaskRecord::completion)
                        .toArray(java.util.concurrent.CompletableFuture[]::new);
                if (futures.length == 0) continue;
                try {
                    java.util.concurrent.CompletableFuture.anyOf(futures)
                            .get(remaining, TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    timedOut = !ready(records, returnWhen);
                    break;
                }
            }
            if (timedOut) {
                for (SubAgentTaskRecord record : records) {
                    if (record.completion().isDone()) continue;
                    switch (onTimeout) {
                        case "RESUME_SESSION" -> subAgentManager.detachTask(record);
                        case "CANCEL" -> record.requestCancel();
                        case "RETURN_PENDING" -> { }
                    }
                }
            }
            ObjectNode result = mapper.createObjectNode().put("wait_timed_out", timedOut);
            ArrayNode completed = result.putArray("completed");
            ArrayNode deferred = result.putArray("deferred");
            List<SubAgentResult> delivered = new java.util.ArrayList<>();
            for (SubAgentTaskRecord record : records) {
                ObjectNode task = mapper.createObjectNode().put("task_id", record.taskId());
                if (record.completion().isDone() && SubAgentToolHelper.consumeInline(record)) {
                    SubAgentResult snapshot = record.completion().join();
                    SubAgentToolHelper.serializeResult(task, snapshot, mapper);
                    delivered.add(snapshot);
                    completed.add(task);
                } else {
                    task.put("status", record.status().get().name());
                    ResultDeliveryState delivery = record.deliveryState().get();
                    task.put("delivery", delivery == ResultDeliveryState.DETACHED
                            || delivery == ResultDeliveryState.SESSION_RESUMED ? "RESUME_SESSION" : "PENDING");
                    deferred.add(task);
                }
            }
            return ToolExecutionOutcome.succeeded(
                    SubAgentToolHelper.output(result, delivered, mapper),
                    deferred.isEmpty() ? ResultStatus.AVAILABLE : ResultStatus.PENDING);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Sub-agent wait interrupted");
        } catch (java.util.concurrent.ExecutionException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ToolExecutionException("await_subagents", "Await failed: " + e.getMessage(), e);
        }
    }

    private static boolean ready(List<SubAgentTaskRecord> records, String returnWhen) {
        if (records.stream().allMatch(r -> r.completion().isDone())) return true;
        return switch (returnWhen) {
            case "ANY" -> records.stream().anyMatch(r -> r.completion().isDone());
            case "FIRST_SUCCESS" -> records.stream().anyMatch(r ->
                    r.completion().isDone() && r.status().get() == SubAgentStatus.SUCCEEDED);
            default -> false;
        };
    }
}

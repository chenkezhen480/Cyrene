package com.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ToolResult;
import com.harness.core.model.ToolSpec;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
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
        return new ToolSpec(
                "await_subagents",
                "Wait for sub-agent tasks to complete and get their results. " +
                        "All tasks share a single timeout deadline. Uncompleted tasks can auto-resume session.",
                mapper.createObjectNode()
                        .put("type", "object")
                        .<ObjectNode>set("properties",
                                mapper.createObjectNode()
                                        .<ObjectNode>set("task_ids",
                                                mapper.createObjectNode()
                                                        .put("type", "array")
                                                        .put("description", "List of task IDs to wait for")
                                                        .<ObjectNode>set("items",
                                                                mapper.createObjectNode().put("type", "string")))
                                        .<ObjectNode>set("return_when",
                                                mapper.createObjectNode()
                                                        .put("type", "string")
                                                        .put("description", "When to return: ALL (wait for all), ANY (wait for any), FIRST_SUCCESS (wait for first success)")
                                                        .put("enum", mapper.createArrayNode().add("ALL").add("ANY").add("FIRST_SUCCESS")))
                                        .<ObjectNode>set("timeout_seconds",
                                                mapper.createObjectNode()
                                                        .put("type", "integer")
                                                        .put("description", "Shared timeout for all tasks in seconds (default from env: 120s)"))
                                        .<ObjectNode>set("on_timeout",
                                                mapper.createObjectNode()
                                                        .put("type", "string")
                                                        .put("description", "Action on timeout: RESUME_SESSION (auto-resume when done), RETURN_PENDING (just return status), CANCEL (cancel remaining)")
                                                        .put("enum", mapper.createArrayNode().add("RESUME_SESSION").add("RETURN_PENDING").add("CANCEL"))))
                        .<ObjectNode>set("required",
                                mapper.createArrayNode().add("task_ids"))
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        AgentRunContext runContext = SpawnSubAgentTool.getCurrentRunContext();
        if (runContext == null) {
            throw new ToolExecutionException("await_subagents", "No active run context.");
        }

        // Parse task IDs
        List<String> taskIds = new ArrayList<>();
        if (arguments.has("task_ids") && arguments.get("task_ids").isArray()) {
            arguments.get("task_ids").forEach(node -> taskIds.add(node.asText()));
        }
        if (taskIds.isEmpty()) {
            throw new ToolExecutionException("await_subagents", "Missing required parameter: task_ids");
        }

        // Parse return_when
        String returnWhen = arguments.has("return_when") ? arguments.get("return_when").asText().toUpperCase() : "ALL";

        // Parse timeout (shared deadline for all tasks)
        int defaultTimeout = EnvConfig.get().getInt(EnvKey.AGENT_AWAIT_TIMEOUT_SECONDS, 120);
        int timeoutSeconds = arguments.has("timeout_seconds") ? arguments.get("timeout_seconds").asInt() : defaultTimeout;

        // Parse on_timeout
        String onTimeout = arguments.has("on_timeout") ? arguments.get("on_timeout").asText().toUpperCase() : "RESUME_SESSION";

        log.info("[AwaitSubAgents] Awaiting tasks: ids={}, returnWhen={}, timeout={}s, onTimeout={}",
                taskIds, returnWhen, timeoutSeconds, onTimeout);

        SubAgentRunScope scope = subAgentManager.getScope(runContext.runId());
        if (scope == null) {
            throw new ToolExecutionException("await_subagents", "No scope found for run " + runContext.runId());
        }

        try {
            ObjectNode result = mapper.createObjectNode();

            switch (returnWhen) {
                case "ANY" -> awaitAny(scope, taskIds, timeoutSeconds, onTimeout, result);
                case "FIRST_SUCCESS" -> awaitFirstSuccess(scope, taskIds, timeoutSeconds, onTimeout, result);
                default -> awaitAll(scope, taskIds, timeoutSeconds, onTimeout, result);
            }

            ToolResult.setCurrentStatus(ToolResult.ResultStatus.SUCCESS);
            return mapper.writeValueAsString(result);

        } catch (Exception e) {
            log.error("[AwaitSubAgents] Error: {}", e.getMessage());
            throw new ToolExecutionException("await_subagents", "Await failed: " + e.getMessage(), e);
        }
    }

    /**
     * Wait for all tasks to complete. Shared deadline across all tasks.
     * Completed tasks are consumed inline; timed-out tasks are detached or cancelled.
     */
    private void awaitAll(SubAgentRunScope scope, List<String> taskIds, int timeoutSeconds,
                          String onTimeout, ObjectNode result) {
        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        List<SubAgentTaskRecord> completedRecords = new ArrayList<>();
        List<SubAgentTaskRecord> pendingRecords = new ArrayList<>();

        // Resolve task records
        List<SubAgentTaskRecord> records = new ArrayList<>();
        for (String taskId : taskIds) {
            SubAgentTaskRecord record = scope.getTask(taskId);
            if (record != null) {
                records.add(record);
            }
        }

        // Wait for each task with shared deadline
        for (SubAgentTaskRecord record : records) {
            if (record.isTerminal()) {
                // Already done, consume inline
                if (record.consumeInline()) {
                    completedRecords.add(record);
                } else {
                    pendingRecords.add(record); // Was detached, include in pending
                }
                continue;
            }

            long remainingMs = Instant.now().until(deadline, java.time.temporal.ChronoUnit.MILLIS);
            if (remainingMs <= 0) {
                // Deadline already passed, handle below
                pendingRecords.add(record);
                continue;
            }

            try {
                record.completion().get(remainingMs, TimeUnit.MILLISECONDS);
                if (record.consumeInline()) {
                    completedRecords.add(record);
                } else {
                    pendingRecords.add(record);
                }
            } catch (TimeoutException e) {
                // Timeout for this task
                log.info("[AwaitSubAgents] Task {} await timed out", record.taskId());
                pendingRecords.add(record);
            } catch (Exception e) {
                log.error("[AwaitSubAgents] Task {} wait error: {}", record.taskId(), e.getMessage());
                if (record.consumeInline()) {
                    completedRecords.add(record);
                }
            }
        }

        // Handle pending tasks based on onTimeout strategy
        applyTimeoutStrategy(pendingRecords, onTimeout);

        // Build response
        buildResponse(result, completedRecords, pendingRecords, onTimeout);
    }

    /**
     * Wait for any task to complete. Returns as soon as one finishes.
     */
    private void awaitAny(SubAgentRunScope scope, List<String> taskIds, int timeoutSeconds,
                          String onTimeout, ObjectNode result) {
        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        List<SubAgentTaskRecord> completedRecords = new ArrayList<>();
        List<SubAgentTaskRecord> pendingRecords = new ArrayList<>();

        List<SubAgentTaskRecord> records = new ArrayList<>();
        for (String taskId : taskIds) {
            SubAgentTaskRecord record = scope.getTask(taskId);
            if (record != null) records.add(record);
        }

        // Check if any already done
        for (SubAgentTaskRecord record : records) {
            if (record.isTerminal()) {
                if (record.consumeInline()) {
                    completedRecords.add(record);
                } else {
                    pendingRecords.add(record);
                }
                // Rest are pending
                for (SubAgentTaskRecord r : records) {
                    if (r != record) pendingRecords.add(r);
                }
                applyTimeoutStrategy(pendingRecords, onTimeout);
                buildResponse(result, completedRecords, pendingRecords, onTimeout);
                return;
            }
        }

        // Wait for first completion
        while (Instant.now().isBefore(deadline)) {
            for (SubAgentTaskRecord record : records) {
                if (record.isTerminal()) {
                    if (record.consumeInline()) {
                        completedRecords.add(record);
                    } else {
                        pendingRecords.add(record);
                    }
                    for (SubAgentTaskRecord r : records) {
                        if (r != record) pendingRecords.add(r);
                    }
                    applyTimeoutStrategy(pendingRecords, onTimeout);
                    buildResponse(result, completedRecords, pendingRecords, onTimeout);
                    return;
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Timeout: all are pending
        pendingRecords.addAll(records);
        applyTimeoutStrategy(pendingRecords, onTimeout);
        buildResponse(result, completedRecords, pendingRecords, onTimeout);
    }

    /**
     * Wait for first successful task.
     */
    private void awaitFirstSuccess(SubAgentRunScope scope, List<String> taskIds, int timeoutSeconds,
                                   String onTimeout, ObjectNode result) {
        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        List<SubAgentTaskRecord> completedRecords = new ArrayList<>();
        List<SubAgentTaskRecord> pendingRecords = new ArrayList<>();

        List<SubAgentTaskRecord> records = new ArrayList<>();
        for (String taskId : taskIds) {
            SubAgentTaskRecord record = scope.getTask(taskId);
            if (record != null) records.add(record);
        }

        // Check if any already succeeded
        for (SubAgentTaskRecord record : records) {
            if (record.isTerminal() && record.status().get() == SubAgentStatus.SUCCEEDED) {
                if (record.consumeInline()) {
                    completedRecords.add(record);
                } else {
                    pendingRecords.add(record);
                }
                for (SubAgentTaskRecord r : records) {
                    if (r != record) pendingRecords.add(r);
                }
                applyTimeoutStrategy(pendingRecords, onTimeout);
                buildResponse(result, completedRecords, pendingRecords, onTimeout);
                return;
            }
        }

        // Wait for first success
        while (Instant.now().isBefore(deadline)) {
            for (SubAgentTaskRecord record : records) {
                if (record.isTerminal() && record.status().get() == SubAgentStatus.SUCCEEDED) {
                    if (record.consumeInline()) {
                        completedRecords.add(record);
                    } else {
                        pendingRecords.add(record);
                    }
                    for (SubAgentTaskRecord r : records) {
                        if (r != record) pendingRecords.add(r);
                    }
                    applyTimeoutStrategy(pendingRecords, onTimeout);
                    buildResponse(result, completedRecords, pendingRecords, onTimeout);
                    return;
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Timeout
        pendingRecords.addAll(records);
        applyTimeoutStrategy(pendingRecords, onTimeout);
        buildResponse(result, completedRecords, pendingRecords, onTimeout);
    }

    /**
     * Apply timeout strategy to pending (uncompleted) tasks.
     */
    private void applyTimeoutStrategy(List<SubAgentTaskRecord> pending, String onTimeout) {
        for (SubAgentTaskRecord record : pending) {
            if (record.isTerminal()) {
                // Already completed (race), consume inline
                record.consumeInline();
                continue;
            }

            switch (onTimeout) {
                case "RESUME_SESSION" -> {
                    // Detach: result will go to SessionInbox when task completes
                    if (record.detach()) {
                        log.info("[AwaitSubAgents] Task {} detached for session resume", record.taskId());
                    }
                }
                case "CANCEL" -> {
                    record.requestCancel();
                    log.info("[AwaitSubAgents] Task {} cancel requested", record.taskId());
                }
                case "RETURN_PENDING" -> {
                    // Do nothing, just return status
                }
            }
        }
    }

    /**
     * Build JSON response with completed and deferred task arrays.
     */
    private void buildResponse(ObjectNode result, List<SubAgentTaskRecord> completed,
                               List<SubAgentTaskRecord> pending, String onTimeout) {
        boolean waitTimedOut = !pending.isEmpty();
        result.put("wait_timed_out", waitTimedOut);

        // Completed tasks
        ArrayNode completedArray = mapper.createArrayNode();
        for (SubAgentTaskRecord record : completed) {
            ObjectNode taskNode = mapper.createObjectNode();
            taskNode.put("task_id", record.taskId());
            taskNode.put("status", record.status().get().name());
            SubAgentResult subResult = record.storedResult();
            if (subResult != null) {
                if (subResult.success()) {
                    taskNode.put("output", subResult.output());
                    taskNode.put("steps", subResult.steps().size());
                    taskNode.put("duration_ms", subResult.durationMs());
                } else {
                    taskNode.put("error", subResult.output());
                }
            }
            completedArray.add(taskNode);
        }
        result.set("completed", completedArray);

        // Deferred/pending tasks
        ArrayNode deferredArray = mapper.createArrayNode();
        for (SubAgentTaskRecord record : pending) {
            ObjectNode taskNode = mapper.createObjectNode();
            taskNode.put("task_id", record.taskId());
            taskNode.put("status", record.status().get().name());
            if (record.isTerminal()) {
                // Completed while we were building response
                SubAgentResult subResult = record.storedResult();
                if (subResult != null && subResult.success()) {
                    taskNode.put("output", subResult.output());
                }
            } else {
                taskNode.put("delivery", record.isDetached() ? "RESUME_SESSION" : "PENDING");
            }
            deferredArray.add(taskNode);
        }
        result.set("deferred", deferredArray);

        // Message
        if (waitTimedOut && onTimeout.equals("RESUME_SESSION")) {
            result.put("message", pending.size() + " task(s) still running. Results will be delivered via session resume when complete.");
        } else if (waitTimedOut && onTimeout.equals("RETURN_PENDING")) {
            result.put("message", pending.size() + " task(s) still running. Use get_subagents to check status.");
        } else if (waitTimedOut && onTimeout.equals("CANCEL")) {
            result.put("message", pending.size() + " task(s) were cancelled due to timeout.");
        }
    }
}

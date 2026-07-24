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

    // ===== Await strategies =====

    /**
     * Wait for all tasks to complete. Shared deadline across all tasks.
     */
    private void awaitAll(SubAgentRunScope scope, List<String> taskIds, int timeoutSeconds,
                          String onTimeout, ObjectNode result) {
        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        List<SubAgentTaskRecord> completedRecords = new ArrayList<>();
        List<SubAgentTaskRecord> pendingRecords = new ArrayList<>();
        List<SubAgentTaskRecord> records = SubAgentToolHelper.resolveTaskRecords(scope, taskIds);

        for (SubAgentTaskRecord record : records) {
            if (record.isTerminal()) {
                addToCompletedOrPending(record, completedRecords, pendingRecords);
                continue;
            }

            long remainingMs = Instant.now().until(deadline, java.time.temporal.ChronoUnit.MILLIS);
            if (remainingMs <= 0) {
                pendingRecords.add(record);
                continue;
            }

            try {
                record.completion().get(remainingMs, TimeUnit.MILLISECONDS);
                addToCompletedOrPending(record, completedRecords, pendingRecords);
            } catch (TimeoutException e) {
                log.info("[AwaitSubAgents] Task {} await timed out", record.taskId());
                pendingRecords.add(record);
            } catch (Exception e) {
                log.error("[AwaitSubAgents] Task {} wait error: {}", record.taskId(), e.getMessage());
                addToCompletedOrPending(record, completedRecords, pendingRecords);
            }
        }

        applyTimeoutStrategy(pendingRecords, onTimeout);
        buildResponse(result, completedRecords, pendingRecords, onTimeout);
    }

    /**
     * Wait for any task to complete. Returns as soon as one finishes.
     */
    private void awaitAny(SubAgentRunScope scope, List<String> taskIds, int timeoutSeconds,
                          String onTimeout, ObjectNode result) {
        awaitFirstMatch(scope, taskIds, timeoutSeconds, onTimeout, result, r -> r.isTerminal());
    }

    /**
     * Wait for first successful task.
     */
    private void awaitFirstSuccess(SubAgentRunScope scope, List<String> taskIds, int timeoutSeconds,
                                   String onTimeout, ObjectNode result) {
        awaitFirstMatch(scope, taskIds, timeoutSeconds, onTimeout, result,
                r -> r.isTerminal() && r.status().get() == SubAgentStatus.SUCCEEDED);
    }

    /**
     * Shared poll loop for ANY and FIRST_SUCCESS modes.
     * Returns as soon as a record matches the predicate, or on timeout.
     */
    private void awaitFirstMatch(SubAgentRunScope scope, List<String> taskIds, int timeoutSeconds,
                                 String onTimeout, ObjectNode result,
                                 java.util.function.Predicate<SubAgentTaskRecord> match) {
        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        List<SubAgentTaskRecord> completedRecords = new ArrayList<>();
        List<SubAgentTaskRecord> pendingRecords = new ArrayList<>();
        List<SubAgentTaskRecord> records = SubAgentToolHelper.resolveTaskRecords(scope, taskIds);

        // Check if any already match
        for (SubAgentTaskRecord record : records) {
            if (match.test(record)) {
                addToCompletedOrPending(record, completedRecords, pendingRecords);
                addRemainingAsPending(records, record, pendingRecords);
                applyTimeoutStrategy(pendingRecords, onTimeout);
                buildResponse(result, completedRecords, pendingRecords, onTimeout);
                return;
            }
        }

        // Poll until match or timeout
        while (Instant.now().isBefore(deadline)) {
            for (SubAgentTaskRecord record : records) {
                if (match.test(record)) {
                    addToCompletedOrPending(record, completedRecords, pendingRecords);
                    addRemainingAsPending(records, record, pendingRecords);
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

    // ===== Helpers =====

    /**
     * Try to consume inline; add to completed list on success, pending list otherwise.
     */
    private void addToCompletedOrPending(SubAgentTaskRecord record,
                                         List<SubAgentTaskRecord> completed,
                                         List<SubAgentTaskRecord> pending) {
        if (record.consumeInline()) {
            completed.add(record);
        } else {
            pending.add(record);
        }
    }

    /**
     * Add all records except the matched one to the pending list.
     */
    private void addRemainingAsPending(List<SubAgentTaskRecord> all, SubAgentTaskRecord matched,
                                       List<SubAgentTaskRecord> pending) {
        for (SubAgentTaskRecord r : all) {
            if (r != matched) pending.add(r);
        }
    }

    /**
     * Apply timeout strategy to pending (uncompleted) tasks.
     */
    private void applyTimeoutStrategy(List<SubAgentTaskRecord> pending, String onTimeout) {
        for (SubAgentTaskRecord record : pending) {
            if (record.isTerminal()) {
                record.consumeInline();
                continue;
            }
            switch (onTimeout) {
                case "RESUME_SESSION" -> {
                    if (record.detach()) {
                        log.info("[AwaitSubAgents] Task {} detached for session resume", record.taskId());
                    }
                }
                case "CANCEL" -> {
                    record.requestCancel();
                    log.info("[AwaitSubAgents] Task {} cancel requested", record.taskId());
                }
                case "RETURN_PENDING" -> { /* just return status */ }
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
            SubAgentToolHelper.serializeResult(taskNode, record.storedResult(), mapper);
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
                SubAgentToolHelper.serializeResult(taskNode, record.storedResult(), mapper);
            } else {
                taskNode.put("delivery", record.isDetached() ? "RESUME_SESSION" : "PENDING");
            }
            deferredArray.add(taskNode);
        }
        result.set("deferred", deferredArray);

        // Message
        if (waitTimedOut) {
            switch (onTimeout) {
                case "RESUME_SESSION" -> result.put("message",
                        pending.size() + " task(s) still running. Results will be delivered via session resume when complete.");
                case "RETURN_PENDING" -> result.put("message",
                        pending.size() + " task(s) still running. Use get_subagents to check status.");
                case "CANCEL" -> result.put("message",
                        pending.size() + " task(s) were cancelled due to timeout.");
            }
        }
    }
}

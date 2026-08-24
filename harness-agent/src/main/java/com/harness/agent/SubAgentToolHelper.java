package com.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Shared parsing and validation logic for sub-agent tools.
 * Extracted to avoid duplication across AwaitSubAgentsTool, GetSubAgentsTool, CancelSubAgentsTool.
 */
final class SubAgentToolHelper {

    private SubAgentToolHelper() {}

    /**
     * Parse task_ids from tool arguments. Accepts both JSON array and comma-separated string.
     * Returns empty list if not present (caller decides if that's an error).
     */
    static List<String> parseTaskIds(JsonNode arguments) {
        List<String> taskIds = new ArrayList<>();
        if (arguments.has("task_ids")) {
            JsonNode node = arguments.get("task_ids");
            if (node.isArray()) {
                node.forEach(n -> taskIds.add(n.asText()));
            } else if (node.isTextual()) {
                for (String id : node.asText().split(",")) {
                    String trimmed = id.trim();
                    if (!trimmed.isEmpty()) taskIds.add(trimmed);
                }
            }
        }
        return taskIds;
    }

    /**
     * Get the current run context, throw if absent.
     */
    static AgentRunContext requireRunContext(String toolName) {
        AgentRunContext ctx = SpawnSubAgentTool.getCurrentRunContext();
        if (ctx == null) {
            throw new ToolExecutionException(toolName, "No active run context.");
        }
        return ctx;
    }

    /**
     * Get the scope for the given run context, throw if absent.
     */
    static SubAgentRunScope requireScope(SubAgentManager manager, AgentRunContext ctx, String toolName) {
        SubAgentRunScope scope = manager.getScope(ctx.runId());
        if (scope == null) {
            throw new ToolExecutionException(toolName, "No scope found for run " + ctx.runId());
        }
        return scope;
    }

    /**
     * Resolve task IDs to task records and reject unknown IDs explicitly.
     */
    static List<SubAgentTaskRecord> resolveTaskRecords(
            SubAgentRunScope scope, List<String> taskIds, String toolName) {
        List<SubAgentTaskRecord> records = new ArrayList<>();
        LinkedHashSet<String> unknownTaskIds = new LinkedHashSet<>();
        for (String taskId : taskIds) {
            SubAgentTaskRecord record = scope.getTask(taskId);
            if (record != null) {
                records.add(record);
            } else {
                unknownTaskIds.add(taskId);
            }
        }
        if (!unknownTaskIds.isEmpty()) {
            throw new ToolExecutionException(
                    toolName, "Unknown task IDs: " + String.join(", ", unknownTaskIds));
        }
        return records;
    }

    /**
     * Serialize a task result into a JSON node.
     * Used by both GetSubAgentsTool and AwaitSubAgentsTool.
     * Full ReAct steps stay in the linked sub-trace and are never copied here.
     */
    static void serializeResult(ObjectNode taskNode, SubAgentResult result, ObjectMapper mapper) {
        if (result == null) {
            return;
        }
        if (result.traceId() != null) {
            taskNode.put("sub_trace_id", result.traceId());
        }
        taskNode.put("success", result.success());
        taskNode.put("status", result.status().name());
        taskNode.put("duration_ms", result.durationMs());
        if (result.output() != null) {
            taskNode.put("output", result.output());
        }
        if (result.error() != null) {
            taskNode.put("error", result.error());
        }

        ArrayNode artifacts = mapper.createArrayNode();
        result.artifacts().forEach(artifact -> {
            ObjectNode artifactNode = mapper.createObjectNode();
            artifactNode.put("id", artifact.id());
            artifactNode.put("name", artifact.name());
            artifactNode.put("type", artifact.type().name());
            artifactNode.put("mime_type", artifact.mimeType());
            artifactNode.put("size_bytes", artifact.sizeBytes());
            artifactNode.put("download_url", artifact.downloadUrl());
            artifactNode.put("preview_url", artifact.previewUrl());
            artifacts.add(artifactNode);
        });
        taskNode.set("artifacts", artifacts);

        ObjectNode summaryNode = mapper.createObjectNode();
        summaryNode.put("total_executions", result.toolExecutionSummary().totalExecutions());
        ObjectNode toolsNode = mapper.createObjectNode();
        result.toolExecutionSummary().tools().forEach((toolName, stats) -> {
            ObjectNode statsNode = mapper.createObjectNode();
            statsNode.put("attempt_count", stats.attemptCount());
            statsNode.put("successful_count", stats.successfulCount());
            statsNode.put("failed_count", stats.failedCount());
            if (stats.latestError() != null) {
                statsNode.put("latest_error", stats.latestError());
            }
            toolsNode.set(toolName, statsNode);
        });
        summaryNode.set("tools", toolsNode);
        taskNode.set("tool_execution_summary", summaryNode);

        ContractValidation validation = result.contractValidation();
        ObjectNode validationNode = mapper.createObjectNode();
        validationNode.put("declared", validation.declared());
        validationNode.put("satisfied", validation.satisfied());
        validationNode.put("status", validation.status().name());
        validationNode.set("violations", mapper.valueToTree(validation.violations()));
        taskNode.set("contract_validation", validationNode);

        if (result.structuredOutput() != null) {
            taskNode.set("structured_output", result.structuredOutput());
        }
    }
}

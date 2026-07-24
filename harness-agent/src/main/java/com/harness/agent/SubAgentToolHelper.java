package com.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ReActStep;

import java.util.ArrayList;
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
     * Resolve task IDs to task records. Unknown IDs are silently skipped.
     */
    static List<SubAgentTaskRecord> resolveTaskRecords(SubAgentRunScope scope, List<String> taskIds) {
        List<SubAgentTaskRecord> records = new ArrayList<>();
        for (String taskId : taskIds) {
            SubAgentTaskRecord record = scope.getTask(taskId);
            if (record != null) {
                records.add(record);
            }
        }
        return records;
    }

    /**
     * Serialize a task result into a JSON node.
     * Used by both GetSubAgentsTool and AwaitSubAgentsTool.
     * Includes sub-agent steps for parent trace recording.
     */
    static void serializeResult(ObjectNode taskNode, SubAgentResult result, ObjectMapper mapper) {
        if (result == null) return;
        if (result.traceId() != null) {
            taskNode.put("sub_trace_id", result.traceId());
        }
        if (result.success()) {
            taskNode.put("output", result.output());
            taskNode.put("steps", result.steps().size());
            taskNode.put("duration_ms", result.durationMs());
            if (result.steps() != null && !result.steps().isEmpty()) {
                taskNode.set("step_details", serializeSteps(result.steps(), mapper));
            }
        } else {
            taskNode.put("error", result.output());
            if (result.steps() != null && !result.steps().isEmpty()) {
                taskNode.set("step_details", serializeSteps(result.steps(), mapper));
            }
        }
    }

    /**
     * Serialize sub-agent ReAct steps into a compact JSON array.
     * Each entry contains: step number, action, tool call count, and truncated observation.
     * This data is included in the parent's tool result for trace visibility.
     */
    static ArrayNode serializeSteps(List<ReActStep> steps, ObjectMapper mapper) {
        ArrayNode stepsArray = mapper.createArrayNode();
        for (ReActStep step : steps) {
            ObjectNode stepNode = mapper.createObjectNode();
            stepNode.put("step", step.stepNumber());
            if (step.action() != null) stepNode.put("action", step.action());
            if (step.toolCalls() != null && !step.toolCalls().isEmpty()) {
                stepNode.put("tool_count", step.toolCalls().size());
                ArrayNode toolsArray = mapper.createArrayNode();
                for (var tc : step.toolCalls()) {
                    toolsArray.add(tc.toolName());
                }
                stepNode.set("tools", toolsArray);
            }
            if (step.observation() != null) {
                String obs = step.observation();
                stepNode.put("observation", obs.length() > 500 ? obs.substring(0, 500) + "..." : obs);
            }
            if (step.inspection() != null) {
                stepNode.put("inspection", step.inspection().status().name());
            }
            stepsArray.add(stepNode);
        }
        return stepsArray;
    }
}

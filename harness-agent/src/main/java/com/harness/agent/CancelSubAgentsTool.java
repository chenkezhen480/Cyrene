package com.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ToolResult;
import com.harness.core.model.ToolSpec;
import com.harness.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Tool to cancel sub-agent tasks.
 * Only allows cancelling tasks in the current run scope.
 */
public class CancelSubAgentsTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CancelSubAgentsTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final SubAgentManager subAgentManager;

    public CancelSubAgentsTool(SubAgentManager subAgentManager) {
        this.subAgentManager = subAgentManager;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "cancel_subagents",
                "Cancel sub-agent tasks. Only tasks in the current run can be cancelled. " +
                        "Returns lists of cancelled tasks and already completed tasks.",
                mapper.createObjectNode()
                        .put("type", "object")
                        .<ObjectNode>set("properties",
                                mapper.createObjectNode()
                                        .<ObjectNode>set("task_ids",
                                                mapper.createObjectNode()
                                                        .put("type", "array")
                                                        .put("description", "List of task IDs to cancel")
                                                        .<ObjectNode>set("items",
                                                                mapper.createObjectNode().put("type", "string"))))
                        .<ObjectNode>set("required",
                                mapper.createArrayNode().add("task_ids"))
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        AgentRunContext runContext = SubAgentToolHelper.requireRunContext("cancel_subagents");

        List<String> taskIds = SubAgentToolHelper.parseTaskIds(arguments);
        if (taskIds.isEmpty()) {
            throw new ToolExecutionException("cancel_subagents", "Missing required parameter: task_ids");
        }

        SubAgentRunScope scope = SubAgentToolHelper.requireScope(subAgentManager, runContext, "cancel_subagents");

        try {
            ObjectNode result = mapper.createObjectNode();
            ArrayNode cancelled = mapper.createArrayNode();
            ArrayNode alreadyCompleted = mapper.createArrayNode();
            ArrayNode notFound = mapper.createArrayNode();

            for (String taskId : taskIds) {
                SubAgentTaskRecord record = scope.getTask(taskId);
                if (record == null) {
                    notFound.add(taskId);
                } else if (record.isTerminal()) {
                    alreadyCompleted.add(taskId);
                } else if (record.requestCancel()) {
                    cancelled.add(taskId);
                    log.info("[CancelSubAgents] Cancel requested for task {}", taskId);
                } else {
                    alreadyCompleted.add(taskId);
                }
            }

            result.set("cancelled", cancelled);
            result.set("already_completed", alreadyCompleted);
            result.set("not_found", notFound);
            result.put("total_requested", taskIds.size());

            ToolResult.setCurrentStatus(ToolResult.ResultStatus.SUCCESS);
            return mapper.writeValueAsString(result);

        } catch (Exception e) {
            log.error("[CancelSubAgents] Error: {}", e.getMessage());
            throw new ToolExecutionException("cancel_subagents", "Cancel failed: " + e.getMessage(), e);
        }
    }
}

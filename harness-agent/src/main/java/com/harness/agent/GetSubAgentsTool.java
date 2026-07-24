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
import java.util.Map;

/**
 * Tool to query sub-agent task status without blocking.
 */
public class GetSubAgentsTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(GetSubAgentsTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final SubAgentManager subAgentManager;

    public GetSubAgentsTool(SubAgentManager subAgentManager) {
        this.subAgentManager = subAgentManager;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "get_subagents",
                "Get the status of sub-agent tasks without blocking. " +
                        "Returns task status, creation time, and results if completed.",
                mapper.createObjectNode()
                        .put("type", "object")
                        .<ObjectNode>set("properties",
                                mapper.createObjectNode()
                                        .<ObjectNode>set("task_ids",
                                                mapper.createObjectNode()
                                                        .put("type", "array")
                                                        .put("description", "List of task IDs to query (empty for all tasks in current run)")
                                                        .<ObjectNode>set("items",
                                                                mapper.createObjectNode().put("type", "string"))))
                        .<ObjectNode>set("required", mapper.createArrayNode())
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        AgentRunContext runContext = SubAgentToolHelper.requireRunContext("get_subagents");
        SubAgentRunScope scope = SubAgentToolHelper.requireScope(subAgentManager, runContext, "get_subagents");

        List<String> taskIds = SubAgentToolHelper.parseTaskIds(arguments);

        try {
            ObjectNode result = mapper.createObjectNode();
            ArrayNode tasksArray = mapper.createArrayNode();

            Map<String, SubAgentTaskRecord> tasks = taskIds.isEmpty()
                    ? scope.getAllTasks()
                    : scope.getTasks(taskIds);

            for (Map.Entry<String, SubAgentTaskRecord> entry : tasks.entrySet()) {
                SubAgentTaskRecord record = entry.getValue();
                ObjectNode taskNode = mapper.createObjectNode();
                taskNode.put("task_id", record.taskId());
                taskNode.put("status", record.status().get().name());
                taskNode.put("created_at", record.createdAt().toString());

                if (record.isTerminal()) {
                    SubAgentResult subResult = record.completion().join();
                    ObjectNode resultNode = taskNode.putObject("result");
                    resultNode.put("success", subResult.success());
                    SubAgentToolHelper.serializeResult(resultNode, subResult, mapper);
                }

                tasksArray.add(taskNode);
            }

            result.set("tasks", tasksArray);
            result.put("total", tasks.size());
            result.put("scope_run_id", runContext.runId());

            ToolResult.setCurrentStatus(ToolResult.ResultStatus.SUCCESS);
            return mapper.writeValueAsString(result);

        } catch (Exception e) {
            log.error("[GetSubAgents] Error: {}", e.getMessage());
            throw new ToolExecutionException("get_subagents", "Query failed: " + e.getMessage(), e);
        }
    }
}

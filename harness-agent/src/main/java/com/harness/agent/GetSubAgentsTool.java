package com.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ResultStatus;
import com.harness.core.model.ToolExecutionOutcome;
import com.harness.core.model.ToolSpec;
import com.harness.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import com.harness.core.model.PageResponse;
import com.harness.core.model.PageInfo;
import com.harness.agent.subagent.SubAgentTaskRepository;

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
                        "Query the current authorized session across runs. Repeated reads preserve delivery state. " +
                        "Use bounded task_ids or cursor/limit to find tasks and completed results.",
                mapper.createObjectNode()
                        .put("type", "object")
                        .<ObjectNode>set("properties",
                                mapper.createObjectNode()
                                        .<ObjectNode>set("task_ids",
                                                mapper.createObjectNode()
                                                        .put("type", "array")
                                                        .put("maxItems", 100)
                                                        .put("description", "Exact task IDs in the authorized session; omit for a paginated directory")
                                                        .<ObjectNode>set("items",
                                                                mapper.createObjectNode().put("type", "string")))
                                        .<ObjectNode>set("limit", mapper.createObjectNode().put("type", "integer").put("minimum", 1).put("maximum", 100))
                                        .<ObjectNode>set("cursor", mapper.createObjectNode().put("type", "string")))
                        .<ObjectNode>set("required", mapper.createArrayNode()),
                com.harness.core.model.ToolCapability.ORCHESTRATION
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        return executeOutcome(arguments).content().modelContent();
    }

    @Override
    public ToolExecutionOutcome executeOutcome(JsonNode arguments) {
        AgentRunContext runContext = SubAgentToolHelper.requireRunContext("get_subagents");
        List<String> taskIds = SubAgentToolHelper.parseTaskIds(arguments);

        try {
            ObjectNode result = mapper.createObjectNode();
            ArrayNode tasksArray = mapper.createArrayNode();
            int limit = arguments.has("limit") ? arguments.get("limit").intValue() : 50;
            if (arguments.has("limit") && !arguments.get("limit").isIntegralNumber()) throw new IllegalArgumentException("limit must be an integer");
            if (arguments.has("cursor") && !arguments.get("cursor").isTextual()) throw new IllegalArgumentException("cursor must be a string");
            String cursor = arguments.path("cursor").asText("");
            PageResponse<SubAgentTaskRepository.StoredTask> page = taskIds.isEmpty()
                    ? subAgentManager.listTasks(runContext.owner(), runContext.sessionId(), cursor, limit)
                    : new PageResponse<>(subAgentManager.findTasks(runContext.owner(), runContext.sessionId(), taskIds),
                            new PageInfo(taskIds.size(), "", false));
            List<SubAgentResult> results = new java.util.ArrayList<>();

            for (var record : page.items()) {
                ObjectNode taskNode = mapper.createObjectNode();
                taskNode.put("taskId", record.taskId());
                taskNode.put("status", record.status().name());
                taskNode.put("deliveryState", record.deliveryState().name());
                taskNode.put("createdAt", record.createdAt().toString());

                if (record.result() != null) {
                    SubAgentResult subResult = record.result();
                    results.add(subResult);
                    ObjectNode resultNode = taskNode.putObject("result");
                    SubAgentToolHelper.serializeResult(resultNode, subResult, mapper);
                } else taskNode.putNull("result");

                tasksArray.add(taskNode);
            }

            result.set("items", tasksArray);
            result.set("pageInfo", mapper.valueToTree(page.pageInfo()));

            return ToolExecutionOutcome.succeeded(
                    SubAgentToolHelper.output(result, results, mapper),
                    ResultStatus.AVAILABLE);

        } catch (Exception e) {
            log.error("[GetSubAgents] Error: {}", e.getMessage());
            throw new ToolExecutionException("get_subagents", "Query failed: " + e.getMessage(), e);
        }
    }
}

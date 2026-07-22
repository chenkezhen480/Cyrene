package com.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ToolResult;
import com.harness.core.model.ToolSpec;
import com.harness.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Built-in tool that allows the main agent to spawn sub-agents for parallel task execution.
 * The sub-agent runs with its own ReActEngine instance but shares the same tool registry.
 */
public class SpawnSubAgentTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SpawnSubAgentTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final SubAgentOrchestrator subAgentOrchestrator;

    public SpawnSubAgentTool(SubAgentOrchestrator subAgentOrchestrator) {
        this.subAgentOrchestrator = subAgentOrchestrator;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "spawn_subagent",
                "Spawn a sub-agent to execute a specific task in parallel. " +
                        "Sub-agents have access to all tools and run with their own context. " +
                        "Use this for independent sub-tasks that can be parallelized.",
                mapper.createObjectNode()
                        .put("type", "object")
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("properties",
                                mapper.createObjectNode()
                                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("task_description",
                                                mapper.createObjectNode()
                                                        .put("type", "string")
                                                        .put("description", "Clear description of the task for the sub-agent"))
                                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("context",
                                                mapper.createObjectNode()
                                                        .put("type", "string")
                                                        .put("description", "Relevant context the sub-agent needs to complete the task"))
                                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("dependencies",
                                                mapper.createObjectNode()
                                                        .put("type", "array")
                                                        .put("description", "Optional list of task IDs this task depends on (must complete first)")
                                                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("items",
                                                                mapper.createObjectNode().put("type", "string"))))
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("required",
                                mapper.createArrayNode().add("task_description").add("context"))
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        String taskDescription = arguments.has("task_description") ? arguments.get("task_description").asText() : null;
        String context = arguments.has("context") ? arguments.get("context").asText() : "";

        if (taskDescription == null || taskDescription.isBlank()) {
            throw new ToolExecutionException("spawn_subagent", "Missing required parameter: task_description");
        }

        List<String> dependencies = new ArrayList<>();
        if (arguments.has("dependencies") && arguments.get("dependencies").isArray()) {
            arguments.get("dependencies").forEach(dep -> dependencies.add(dep.asText()));
        }

        String taskId = "sub-" + System.currentTimeMillis();
        log.info("[SpawnSubAgent] Spawning sub-agent: taskId={}, deps={}", taskId, dependencies);

        try {
            SubAgentTask task = SubAgentTask.create(taskId, taskDescription, context, dependencies);
            CompletableFuture<SubAgentResult> future = subAgentOrchestrator.submitTask(task);

            // Wait for the sub-agent to complete (with timeout)
            SubAgentResult result = future.get(120, TimeUnit.SECONDS);

            if (result.success()) {
                log.info("[SpawnSubAgent] Sub-agent {} completed in {}ms, outputLen={}",
                        taskId, result.durationMs(), result.output() != null ? result.output().length() : 0);
                ToolResult.setCurrentStatus(ToolResult.ResultStatus.SUCCESS);
                return String.format("Sub-agent task %s completed successfully.\n\nResult:\n%s\n\nSteps taken: %d, Duration: %dms",
                        taskId, result.output(), result.steps().size(), result.durationMs());
            } else {
                log.warn("[SpawnSubAgent] Sub-agent {} failed: {}", taskId, result.output());
                ToolResult.setCurrentStatus(ToolResult.ResultStatus.EMPTY);
                return String.format("Sub-agent task %s failed: %s", taskId, result.output());
            }
        } catch (Exception e) {
            log.error("[SpawnSubAgent] Sub-agent {} execution error: {}", taskId, e.getMessage());
            throw new ToolExecutionException("spawn_subagent", "Sub-agent execution failed: " + e.getMessage(), e);
        }
    }
}

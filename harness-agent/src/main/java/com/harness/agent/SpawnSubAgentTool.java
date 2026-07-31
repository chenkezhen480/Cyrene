package com.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ToolResult;
import com.harness.core.model.ToolSpec;
import com.harness.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Built-in tool that spawns a sub-agent for async task execution.
 * Returns immediately with a task handle; use await_subagents to wait for completion.
 *
 * The main agent's LLM is responsible for generating:
 * - persona: specific role/identity for the sub-agent
 * - system_prompt: task-specific system instructions
 * - context: relevant context (summarized/compressed)
 * - tools: (optional) list of tool names the sub-agent needs; defaults to no tools
 */
public class SpawnSubAgentTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SpawnSubAgentTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final SubAgentManager subAgentManager;

    // ThreadLocal to hold the current run context, set by AgentOrchestrator before tool execution
    private static final ThreadLocal<AgentRunContext> currentRunContext = new ThreadLocal<>();

    public SpawnSubAgentTool(SubAgentManager subAgentManager) {
        this.subAgentManager = subAgentManager;
    }

    /**
     * Set the current run context for this thread. Called by AgentOrchestrator before ReAct loop.
     */
    public static void setCurrentRunContext(AgentRunContext context) {
        currentRunContext.set(context);
    }

    /**
     * Clear the current run context. Called in finally block after ReAct loop.
     */
    public static void clearCurrentRunContext() {
        currentRunContext.remove();
    }

    /**
     * Get the current run context. Used by other tools (await, get, cancel).
     */
    public static AgentRunContext getCurrentRunContext() {
        return currentRunContext.get();
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "spawn_subagent",
                "Spawn a sub-agent to execute a specific task in parallel. " +
                        "Returns immediately with a task handle. " +
                        "Use await_subagents to wait for completion and get results.\n\n" +
                        "You MUST provide:\n" +
                        "- persona: specific role/identity for this sub-agent\n" +
                        "- system_prompt: task-specific instructions including methodology, output format, constraints\n" +
                        "- task_description: clear description of what to accomplish\n" +
                        "- context: relevant background info, compressed conversation history, and prior results. " +
                        "The sub-agent has NO access to conversation history — you MUST include relevant history here.\n\n" +
                        "Optionally provide 'tools' to give the sub-agent specific tools. If omitted, the sub-agent has NO tools (text-only analysis).\n\n" +
                        "Available tool names: web_search, knowledge_base_search, image_generation, " +
                        "code_sandbox, load_skill, and any registered MCP tools.",
                mapper.createObjectNode()
                        .put("type", "object")
                        .<ObjectNode>set("properties",
                                mapper.createObjectNode()
                                        .<ObjectNode>set("persona",
                                                mapper.createObjectNode()
                                                        .put("type", "string")
                                                        .put("description", "Specific role/identity for the sub-agent. Define who they are, their expertise, and approach. Example: 'You are a senior Python developer with 10 years of experience in data pipeline optimization.'"))
                                        .<ObjectNode>set("system_prompt",
                                                mapper.createObjectNode()
                                                        .put("type", "string")
                                                        .put("description", "Task-specific system instructions. Include methodology, step-by-step approach, output format requirements, quality criteria, and any constraints. Be specific and actionable."))
                                        .<ObjectNode>set("task_description",
                                                mapper.createObjectNode()
                                                        .put("type", "string")
                                                        .put("description", "Clear, specific description of the task to accomplish. This becomes the user message for the sub-agent."))
                                        .<ObjectNode>set("context",
                                                mapper.createObjectNode()
                                                        .put("type", "string")
                                                        .put("description", "Relevant context for the sub-agent. MUST include:\n" +
                                                                "1. Relevant conversation history — compress and summarize only the parts directly related to this task (e.g. user requirements, prior decisions, key data points)\n" +
                                                                "2. Background info or data the sub-agent needs\n" +
                                                                "3. Prior results from other sub-agents if this task depends on them\n\n" +
                                                                "Keep concise. Omit anything not directly relevant to this specific task. " +
                                                                "The sub-agent has NO access to conversation history — if you don't include it here, the sub-agent won't know it."))
                                        .<ObjectNode>set("tools",
                                                mapper.createObjectNode()
                                                        .put("type", "array")
                                                        .put("description", "List of tool names this sub-agent needs. If omitted, the sub-agent has NO tools (pure text analysis). Include tools like web_search, knowledge_base_search, code_sandbox, etc. when the task requires them.")
                                                        .<ObjectNode>set("items",
                                                                mapper.createObjectNode().put("type", "string")))
                                        .<ObjectNode>set("dependencies",
                                                mapper.createObjectNode()
                                                        .put("type", "array")
                                                        .put("description", "Optional list of task IDs this task depends on (must complete first)")
                                                        .<ObjectNode>set("items",
                                                                mapper.createObjectNode().put("type", "string"))))
                        .<ObjectNode>set("required",
                                mapper.createArrayNode()
                                        .add("persona")
                                        .add("system_prompt")
                                        .add("task_description")
                                        .add("context"))
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        AgentRunContext runContext = currentRunContext.get();
        if (runContext == null) {
            throw new ToolExecutionException("spawn_subagent", "No active run context. This tool must be called within an agent run.");
        }

        // Parse required parameters
        String persona = arguments.has("persona") ? arguments.get("persona").asText() : null;
        String systemPrompt = arguments.has("system_prompt") ? arguments.get("system_prompt").asText() : null;
        String taskDescription = arguments.has("task_description") ? arguments.get("task_description").asText() : null;
        String context = arguments.has("context") ? arguments.get("context").asText() : "";

        if (persona == null || persona.isBlank()) {
            throw new ToolExecutionException("spawn_subagent", "Missing required parameter: persona");
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new ToolExecutionException("spawn_subagent", "Missing required parameter: system_prompt");
        }
        if (taskDescription == null || taskDescription.isBlank()) {
            throw new ToolExecutionException("spawn_subagent", "Missing required parameter: task_description");
        }

        // Parse tool list (optional — empty means no tools)
        List<String> tools = new ArrayList<>();
        if (arguments.has("tools") && arguments.get("tools").isArray()) {
            arguments.get("tools").forEach(node -> tools.add(node.asText()));
        }

        // Parse dependencies
        List<String> dependencies = new ArrayList<>();
        if (arguments.has("dependencies") && arguments.get("dependencies").isArray()) {
            arguments.get("dependencies").forEach(dep -> dependencies.add(dep.asText()));
        }

        String taskId = SubAgentManager.generateTaskId();
        String sessionId = runContext.sessionId();
        log.info("[SpawnSubAgent] Submitting task: taskId={}, runId={}, sessionId={}, tools={}, deps={}",
                taskId, runContext.runId(), sessionId, tools, dependencies);

        try {
            SubAgentTask task = SubAgentTask.create(taskId, taskDescription, context,
                    persona, systemPrompt, tools, dependencies);
            SubAgentTaskRecord record = subAgentManager.submitTask(runContext, task, sessionId);

            if (record == null) {
                throw new ToolExecutionException("spawn_subagent",
                        "Failed to submit task. Possible causes: spawn limit reached, or scope not found.");
            }

            // Return immediately with task handle
            ObjectNode result = mapper.createObjectNode();
            result.put("task_id", taskId);
            result.put("status", record.status().get().name());
            result.put("accepted", true);
            result.put("message", "Task submitted. Use await_subagents to wait for completion.");

            log.info("[SpawnSubAgent] Task {} accepted, status={}", taskId, record.status().get());
            ToolResult.setCurrentStatus(ToolResult.ResultStatus.SUCCESS);
            return mapper.writeValueAsString(result);

        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            log.error("[SpawnSubAgent] Task submission error: {}", e.getMessage());
            throw new ToolExecutionException("spawn_subagent", "Task submission failed: " + e.getMessage(), e);
        }
    }
}

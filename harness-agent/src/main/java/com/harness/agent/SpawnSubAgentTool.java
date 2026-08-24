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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
                        "Optionally provide 'completion_contract' when completion must be verified from successful tool calls, stored artifacts, or structured output.\n\n" +
                        "Available tool names: web_search, knowledge_base_search, knowledge_context_read, image_generation, " +
                        "code_sandbox, load_skill, and any registered MCP tools.",
                buildParametersSchema()
        );
    }

    private static ObjectNode buildParametersSchema() {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("persona", mapper.createObjectNode()
                .put("type", "string")
                .put("description", "Specific role and expertise for the sub-agent."));
        properties.set("system_prompt", mapper.createObjectNode()
                .put("type", "string")
                .put("description", "Task-specific methodology, constraints, and output guidance."));
        properties.set("task_description", mapper.createObjectNode()
                .put("type", "string")
                .put("description", "Clear description of the task to accomplish."));
        properties.set("context", mapper.createObjectNode()
                .put("type", "string")
                .put("description", "Relevant compressed history and background. The sub-agent cannot read the parent conversation."));
        properties.set("tools", stringArraySchema(
                "Allowed tool names. Omit for text-only analysis."));
        properties.set("dependencies", stringArraySchema(
                "Existing task IDs that this task depends on."));

        ObjectNode requiredArtifactProperties = mapper.createObjectNode();
        requiredArtifactProperties.set("artifact_type", mapper.createObjectNode()
                .put("type", "string")
                .put("description", "Artifact type: IMAGE, DOCUMENT, CODE, VIDEO, AUDIO, or OTHER."));
        requiredArtifactProperties.set("allowed_mime_types", stringArraySchema(
                "Allowed MIME types. Omit or use an empty array to allow any MIME type."));
        requiredArtifactProperties.set("min_count", mapper.createObjectNode()
                .put("type", "integer")
                .put("description", "Minimum number of matching stored artifacts."));
        ObjectNode requiredArtifactSchema = mapper.createObjectNode().put("type", "object");
        requiredArtifactSchema.set("properties", requiredArtifactProperties);
        requiredArtifactSchema.set("required", mapper.createArrayNode()
                .add("artifact_type").add("min_count"));

        ObjectNode completionProperties = mapper.createObjectNode();
        completionProperties.set("required_successful_tools", stringArraySchema(
                "Allowed tools that must each execute successfully at least once."));
        ObjectNode requiredArtifacts = mapper.createObjectNode()
                .put("type", "array")
                .put("description", "Artifact requirements verified against ArtifactStore records.");
        requiredArtifacts.set("items", requiredArtifactSchema);
        completionProperties.set("required_artifacts", requiredArtifacts);
        completionProperties.set("output_schema", mapper.createObjectNode()
                .put("type", "object")
                .put("description", "Strict JSON Schema for the sub-agent final summary."));
        ObjectNode completionSchema = mapper.createObjectNode().put("type", "object");
        completionSchema.set("properties", completionProperties);
        completionSchema.set("required", mapper.createArrayNode());
        properties.set("completion_contract", completionSchema);

        ObjectNode parameters = mapper.createObjectNode().put("type", "object");
        parameters.set("properties", properties);
        parameters.set("required", mapper.createArrayNode()
                .add("persona").add("system_prompt")
                .add("task_description").add("context"));
        return parameters;
    }

    private static ObjectNode stringArraySchema(String description) {
        ObjectNode schema = mapper.createObjectNode()
                .put("type", "array")
                .put("description", description);
        schema.set("items", mapper.createObjectNode().put("type", "string"));
        return schema;
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

        SubAgentCompletionContract completionContract = parseCompletionContract(arguments);

        String taskId = SubAgentManager.generateTaskId();
        String sessionId = runContext.sessionId();
        log.info("[SpawnSubAgent] Submitting task: taskId={}, runId={}, sessionId={}, tools={}, deps={}",
                taskId, runContext.runId(), sessionId, tools, dependencies);

        try {
            SubAgentTask task = SubAgentTask.create(taskId, taskDescription, context,
                    persona, systemPrompt, tools, dependencies, completionContract);
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

    private static SubAgentCompletionContract parseCompletionContract(JsonNode arguments) {
        JsonNode contractNode = arguments.get("completion_contract");
        if (contractNode == null || contractNode.isNull()) {
            return null;
        }
        if (!contractNode.isObject()) {
            throw new ToolExecutionException(
                    "spawn_subagent", "completion_contract must be an object");
        }

        Set<String> requiredTools = readStringSet(
                contractNode, "required_successful_tools");
        List<RequiredArtifact> requiredArtifacts = new ArrayList<>();
        JsonNode artifactsNode = contractNode.get("required_artifacts");
        if (artifactsNode != null) {
            if (!artifactsNode.isArray()) {
                throw new ToolExecutionException(
                        "spawn_subagent", "required_artifacts must be an array");
            }
            for (JsonNode artifactNode : artifactsNode) {
                if (!artifactNode.isObject()) {
                    throw new ToolExecutionException(
                            "spawn_subagent", "Each required_artifacts entry must be an object");
                }
                String artifactType = requiredText(artifactNode, "artifact_type");
                int minCount = artifactNode.has("min_count")
                        ? artifactNode.get("min_count").asInt(0)
                        : 0;
                try {
                    requiredArtifacts.add(new RequiredArtifact(
                            artifactType,
                            readStringSet(artifactNode, "allowed_mime_types"),
                            minCount));
                } catch (IllegalArgumentException e) {
                    throw new ToolExecutionException(
                            "spawn_subagent", "Invalid artifact requirement: " + e.getMessage(), e);
                }
            }
        }

        JsonNode outputSchema = contractNode.get("output_schema");
        if (outputSchema != null && !outputSchema.isObject()) {
            throw new ToolExecutionException(
                    "spawn_subagent", "output_schema must be an object");
        }
        return new SubAgentCompletionContract(
                requiredTools, requiredArtifacts, outputSchema);
    }

    private static Set<String> readStringSet(JsonNode objectNode, String fieldName) {
        JsonNode value = objectNode.get(fieldName);
        if (value == null || value.isNull()) {
            return Set.of();
        }
        if (!value.isArray()) {
            throw new ToolExecutionException(
                    "spawn_subagent", fieldName + " must be an array");
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new ToolExecutionException(
                        "spawn_subagent", fieldName + " entries must be non-blank strings");
            }
            values.add(item.asText());
        }
        return Set.copyOf(values);
    }

    private static String requiredText(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new ToolExecutionException(
                    "spawn_subagent", "Missing required artifact field: " + fieldName);
        }
        return value.asText();
    }
}

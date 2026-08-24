package com.harness.agent;

import java.util.List;

/**
 * Describes a sub-task to be executed by a sub-agent.
 * The main agent's LLM generates persona, system_prompt, and tool list for each task.
 */
public record SubAgentTask(
        String taskId,
        String description,
        String context,
        String persona,
        String systemPrompt,
        List<String> tools,
        List<String> dependencies,
        SubAgentCompletionContract completionContract
) {
    public SubAgentTask {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /**
     * Create a task with full specification (persona, system prompt, tools).
     */
    public static SubAgentTask create(String taskId, String description, String context,
                                       String persona, String systemPrompt,
                                       List<String> tools, List<String> dependencies,
                                       SubAgentCompletionContract completionContract) {
        return new SubAgentTask(taskId, description, context, persona, systemPrompt,
                tools, dependencies, completionContract);
    }
}

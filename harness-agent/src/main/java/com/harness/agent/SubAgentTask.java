package com.harness.agent;

import java.util.List;

/**
 * Describes a sub-task to be executed by a sub-agent.
 * Sub-agents receive only the task description and context summary, not full conversation history.
 */
public record SubAgentTask(
        String taskId,
        String description,
        String context,
        List<String> dependencies
) {
    public SubAgentTask {
        if (dependencies == null) dependencies = List.of();
    }

    public static SubAgentTask create(String taskId, String description, String context) {
        return new SubAgentTask(taskId, description, context, List.of());
    }

    public static SubAgentTask create(String taskId, String description, String context, List<String> dependencies) {
        return new SubAgentTask(taskId, description, context, dependencies);
    }
}

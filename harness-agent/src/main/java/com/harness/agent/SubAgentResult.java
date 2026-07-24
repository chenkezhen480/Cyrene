package com.harness.agent;

import com.harness.core.model.ReActStep;

import java.util.List;

/**
 * Result returned by a sub-agent after completing its task.
 */
public record SubAgentResult(
        String taskId,
        String output,
        boolean success,
        List<ReActStep> steps,
        long durationMs,
        String traceId
) {
    public static SubAgentResult success(String taskId, String output, List<ReActStep> steps, long durationMs) {
        return new SubAgentResult(taskId, output, true, steps, durationMs, null);
    }

    public static SubAgentResult success(String taskId, String output, List<ReActStep> steps, long durationMs, String traceId) {
        return new SubAgentResult(taskId, output, true, steps, durationMs, traceId);
    }

    public static SubAgentResult failure(String taskId, String error, List<ReActStep> steps, long durationMs) {
        return new SubAgentResult(taskId, error, false, steps, durationMs, null);
    }
}

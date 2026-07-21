package com.harness.ai.react;

import com.harness.core.model.Artifact;
import com.harness.core.model.ReActStep;

import java.util.List;

/**
 * Callback interface for receiving intermediate ReAct loop events.
 * Used for SSE streaming of agent progress.
 */
public interface ReActListener {

    /**
     * Called after each ReAct loop iteration completes.
     *
     * @param step the completed step with thought, tool calls, results, and inspection
     */
    void onStep(ReActStep step);

    /**
     * Called for each partial text token during streaming.
     * Default is no-op for backward compatibility.
     */
    default void onToken(String token) {}

    /**
     * Called when a tool call begins execution during streaming.
     * Default is no-op for backward compatibility.
     */
    default void onToolCallStart(String toolName, String arguments) {}

    /**
     * Called when a tool call finishes execution.
     * Default is no-op for backward compatibility.
     *
     * @param toolName   the tool that was called
     * @param success    whether the tool returned a successful result
     * @param durationMs execution time in milliseconds
     */
    default void onToolCallDone(String toolName, boolean success, long durationMs) {}

    /**
     * Called when an artifact-producing tool generates downloadable files.
     * Default is no-op for backward compatibility.
     */
    default void onArtifact(List<Artifact> artifacts) {}
}

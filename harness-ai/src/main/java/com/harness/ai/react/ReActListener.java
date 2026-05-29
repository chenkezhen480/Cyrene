package com.harness.ai.react;

import com.harness.core.model.ReActStep;

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
}

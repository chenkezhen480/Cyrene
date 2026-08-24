package com.harness.react;

/** Core execution boundary for blocking and streaming ReAct loops. */
public interface ReActLoop {
    ReActResult execute(ReActRequest request);

    ReActResult streamExecute(ReActRequest request);

    /**
     * Kept for source compatibility. Visible tools now always use an isolated final-response phase.
     */
    @Deprecated
    default ReActResult streamExecute(
            ReActRequest request, boolean legacyFinalAnswerOnlyStreaming) {
        return streamExecute(request);
    }
}

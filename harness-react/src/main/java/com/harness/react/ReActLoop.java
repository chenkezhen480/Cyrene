package com.harness.react;

/** Core execution boundary for blocking and streaming ReAct loops. */
public interface ReActLoop {
    ReActResult execute(ReActRequest request);
    ReActResult streamExecute(ReActRequest request, boolean finalAnswerOnlyStreaming);
}

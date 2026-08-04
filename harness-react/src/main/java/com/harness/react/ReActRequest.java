package com.harness.react;

import com.harness.core.model.CancellationToken;
import com.harness.core.runtime.RunTrace;
import com.harness.tool.confirmation.ConfirmationExecutionContext;
import dev.langchain4j.data.message.ChatMessage;

import java.util.List;
import java.util.Objects;

/** Immutable input for one ReAct loop execution. */
public record ReActRequest(
        String systemPrompt,
        String userMessage,
        List<ChatMessage> historyMessages,
        RunTrace trace,
        ReActListener listener,
        CancellationToken cancellationToken,
        Boolean enableThinking,
        ConfirmationExecutionContext confirmationContext
) {
    public ReActRequest {
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(userMessage, "userMessage");
        historyMessages = historyMessages != null ? List.copyOf(historyMessages) : List.of();
        trace = trace != null ? trace : RunTrace.noop();
    }
}

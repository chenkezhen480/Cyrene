package com.harness.react;

import com.harness.core.model.CancellationToken;
import com.harness.core.model.FinalOutputContract;
import com.harness.core.model.ThinkingLevel;
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
        String dynamicKnowledgeContext,
        RunTrace trace,
        ReActListener listener,
        CancellationToken cancellationToken,
        ThinkingLevel thinkingLevel,
        ConfirmationExecutionContext confirmationContext,
        FinalOutputContract finalOutputContract
) {
    public ReActRequest {
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(userMessage, "userMessage");
        historyMessages = historyMessages != null ? List.copyOf(historyMessages) : List.of();
        dynamicKnowledgeContext = dynamicKnowledgeContext == null
                || dynamicKnowledgeContext.isBlank()
                ? null
                : dynamicKnowledgeContext;
        trace = trace != null ? trace : RunTrace.noop();
        finalOutputContract = finalOutputContract != null
                ? finalOutputContract
                : new FinalOutputContract.Text();
    }

    public ReActRequest(
            String systemPrompt,
            String userMessage,
            List<ChatMessage> historyMessages,
            String dynamicKnowledgeContext,
            RunTrace trace,
            ReActListener listener,
            CancellationToken cancellationToken,
            ThinkingLevel thinkingLevel,
            ConfirmationExecutionContext confirmationContext
    ) {
        this(systemPrompt, userMessage, historyMessages, dynamicKnowledgeContext,
                trace, listener, cancellationToken, thinkingLevel,
                confirmationContext, new FinalOutputContract.Text());
    }

    public ReActRequest(
            String systemPrompt,
            String userMessage,
            List<ChatMessage> historyMessages,
            RunTrace trace,
            ReActListener listener,
            CancellationToken cancellationToken,
            ThinkingLevel thinkingLevel,
            ConfirmationExecutionContext confirmationContext,
            FinalOutputContract finalOutputContract
    ) {
        this(systemPrompt, userMessage, historyMessages, null, trace, listener,
                cancellationToken, thinkingLevel, confirmationContext,
                finalOutputContract);
    }

    public ReActRequest(
            String systemPrompt,
            String userMessage,
            List<ChatMessage> historyMessages,
            RunTrace trace,
            ReActListener listener,
            CancellationToken cancellationToken,
            ThinkingLevel thinkingLevel,
            ConfirmationExecutionContext confirmationContext
    ) {
        this(systemPrompt, userMessage, historyMessages, null, trace, listener,
                cancellationToken, thinkingLevel, confirmationContext,
                new FinalOutputContract.Text());
    }
}

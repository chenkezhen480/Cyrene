package com.harness.react;

import com.harness.core.model.CancellationToken;
import com.harness.provider.ChatModelProvider;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Generates the user-visible final response after tool planning has finished.
 * Tool definitions are deliberately absent from this model call.
 */
public final class FinalResponseGenerator {

    private static final Logger log = LoggerFactory.getLogger(FinalResponseGenerator.class);

    static final String FINAL_ANSWER_INSTRUCTION = """
            <final_answer_phase>
            The execution budget has ended or execution requires user action.
            Summarize only the evidence already obtained and clearly state what remains unresolved.
            No further tools can be executed in this call.
            </final_answer_phase>
            """;

    /** Added only on a retry, after the model already answered this phase with tool syntax. */
    static final String TOOL_SYNTAX_RETRY_INSTRUCTION = """
            <tool_syntax_rejected>
            Your previous attempt emitted tool-call syntax, which cannot be executed here and
            was rejected as a protocol error. Tools are not available in this phase. Write the
            answer as ordinary prose. If you still need a tool, say what you need in a sentence.
            </tool_syntax_rejected>
            """;

    /**
     * Provider tool-call envelopes that must never reach the user as final prose.
     *
     * <p>Deliberately narrow: a false positive would discard a real answer, so only unambiguous
     * protocol delimiters count. The first entry uses the fullwidth bars DeepSeek emits; the
     * ASCII variants cover the same syntax produced by other runtimes.</p>
     */
    private static final List<String> TOOL_CALL_MARKERS = List.of(
            "<｜｜DSML｜｜",
            "<||DSML||",
            "<｜DSML｜",
            "<|DSML|",
            "<tool_call",
            "<tool_calls",
            "<function_call");

    /** Whether this text contains a provider tool-call envelope. */
    static boolean isToolCallMarkup(String text) {
        if (text == null) {
            return false;
        }
        String candidate = text.stripLeading();
        for (String marker : TOOL_CALL_MARKERS) {
            if (candidate.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** Thrown internally so a leaked tool call can be retried instead of shown. */
    static final class ToolCallSyntaxLeakException extends RuntimeException {
        ToolCallSyntaxLeakException() {
            super("Final answer contained tool-call syntax while tools were unavailable");
        }
    }

    public record Result(ChatResponse response, List<ChatMessage> messages) {
        public Result {
            Objects.requireNonNull(response, "response");
            messages = List.copyOf(messages);
        }
    }

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final long timeoutSeconds;
    private final boolean strictChatCompletionFinishReason;

    public FinalResponseGenerator(ChatModelProvider chatModelProvider, long timeoutSeconds) {
        Objects.requireNonNull(chatModelProvider, "chatModelProvider");
        this.chatModel = Objects.requireNonNull(chatModelProvider.chatModel(), "chatModel");
        this.streamingChatModel = chatModelProvider.streamingModel();
        this.strictChatCompletionFinishReason = chatModelProvider.requiresChatCompletionFinishReason();
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be positive");
        }
        this.timeoutSeconds = timeoutSeconds;
    }

    public Result generateStreaming(
            String systemPrompt,
            List<ChatMessage> planningMessages,
            ChatRequestParameters requestParameters,
            ReActListener listener,
            CancellationToken cancellationToken
    ) {
        if (streamingChatModel == null) {
            throw new IllegalStateException("Streaming final response is unavailable");
        }
        if (cancellationToken != null && cancellationToken.isCancelled()) {
            throw new java.util.concurrent.CancellationException(
                    "Final answer generation cancelled");
        }

        try {
            return streamFinalAnswer(
                    finalMessages(systemPrompt, planningMessages),
                    requestParameters, listener, cancellationToken);
        } catch (ToolCallSyntaxLeakException leak) {
            // The model answered a phase with no tools by emitting tool syntax, so it produced
            // nothing the user can read. One retry that names the problem beats showing markup,
            // and nothing was streamed: the leak is caught before the first token is released.
            log.warn("[FinalResponse] {}; regenerating the final answer once", leak.getMessage());
            try {
                return streamFinalAnswer(
                        finalMessages(systemPrompt, planningMessages,
                                TOOL_SYNTAX_RETRY_INSTRUCTION),
                        requestParameters, listener, cancellationToken);
            } catch (ToolCallSyntaxLeakException again) {
                throw new RuntimeException(leak.getMessage(), again);
            }
        }
    }

    private Result streamFinalAnswer(
            List<ChatMessage> finalMessages,
            ChatRequestParameters requestParameters,
            ReActListener listener,
            CancellationToken cancellationToken
    ) {
        ChatRequest.Builder requestBuilder = ChatRequest.builder().messages(finalMessages);
        if (requestParameters != null) {
            requestBuilder.parameters(requestParameters);
        }

        CompletableFuture<ChatResponse> responseFuture = new CompletableFuture<>();
        // A provider can emit protocol markup after ordinary prose. Validate the complete
        // message before publishing any text; SSE frames cannot be retracted.
        // Tracked before the request starts, not after it returns: the streaming HTTP future is
        // keyed by the calling thread, and this thread then blocks on it for the whole stream, so
        // a cancel arriving mid-stream can only reach that future while the thread is registered.
        if (cancellationToken != null) {
            cancellationToken.trackCurrentThread();
        }
        try {
            streamingChatModel.chat(requestBuilder.build(), new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String text) {
                    // Publish only the validated complete answer below.
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    try {
                        requireCompletedAnswer(response);
                        responseFuture.complete(response);
                    } catch (RuntimeException failure) {
                        responseFuture.completeExceptionally(failure);
                    }
                }

                @Override
                public void onError(Throwable error) {
                    responseFuture.completeExceptionally(error);
                }
            });

            ChatResponse response = responseFuture.get(timeoutSeconds, TimeUnit.SECONDS);
            forward(listener, response.aiMessage().text());
            return new Result(response, finalMessages);
        } catch (TimeoutException e) {
            throw new RuntimeException(
                    "Final answer streaming call timed out after " + timeoutSeconds + "s", e);
        } catch (Exception e) {
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                throw new java.util.concurrent.CancellationException(
                        "Final answer generation cancelled");
            }
            Throwable cause = (e instanceof CompletionException || e instanceof ExecutionException)
                    && e.getCause() != null ? e.getCause() : e;
            throw cause instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new RuntimeException(cause);
        } finally {
            if (cancellationToken != null) {
                cancellationToken.untrackCurrentThread();
            }
        }
    }

    static void requireAnswer(ChatResponse response) {
        if (response == null || response.aiMessage() == null) {
            throw new IllegalStateException("Provider returned no assistant message");
        }
        if (response.aiMessage().hasToolExecutionRequests() || isToolCallMarkup(response.aiMessage().text())) {
            throw new ToolCallSyntaxLeakException();
        }
    }

    private void requireCompletedAnswer(ChatResponse response) {
        requireAnswer(response);
        FinishReason reason = response.finishReason();
        if (reason != FinishReason.STOP
                && (strictChatCompletionFinishReason || reason != null)) {
            throw new IllegalStateException(
                    "Final answer did not complete normally: finish_reason=" + reason);
        }
    }

    private static void forward(ReActListener listener, String text) {
        if (listener != null && text != null && !text.isEmpty()) {
            listener.onToken(text);
        }
    }

    public Result generateBlocking(
            String systemPrompt,
            List<ChatMessage> planningMessages,
            ChatRequestParameters requestParameters,
            CancellationToken cancellationToken
    ) {
        if (cancellationToken != null && cancellationToken.isCancelled()) {
            throw new java.util.concurrent.CancellationException(
                    "Final answer generation cancelled");
        }

        List<ChatMessage> finalMessages = finalMessages(systemPrompt, planningMessages);
        ChatRequest request = ChatRequest.builder()
                .messages(finalMessages)
                .parameters(requestParameters != null
                        ? requestParameters
                        : ChatRequestParameters.builder().build())
                .build();

        if (cancellationToken != null) {
            cancellationToken.trackCurrentThread();
        }
        try {
            ChatResponse response = chatModel.chat(request);
            requireCompletedAnswer(response);
            return new Result(response, finalMessages);
        } finally {
            if (cancellationToken != null) {
                cancellationToken.untrackCurrentThread();
            }
        }
    }

    private List<ChatMessage> finalMessages(
            String systemPrompt, List<ChatMessage> planningMessages) {
        return finalMessages(systemPrompt, planningMessages, null);
    }

    private List<ChatMessage> finalMessages(
            String systemPrompt, List<ChatMessage> planningMessages, String extraInstruction) {
        List<ChatMessage> finalMessages = new ArrayList<>(planningMessages);
        String instruction = extraInstruction == null
                ? FINAL_ANSWER_INSTRUCTION
                : FINAL_ANSWER_INSTRUCTION + "\n\n" + extraInstruction;
        finalMessages.set(0, SystemMessage.from(systemPrompt + "\n\n" + instruction));
        return finalMessages;
    }
}

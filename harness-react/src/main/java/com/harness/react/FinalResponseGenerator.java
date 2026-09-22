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

    static final String FINAL_ANSWER_INSTRUCTION = """
            <final_answer_phase>
            Tool use is now disabled. Produce the complete user-facing final answer now.
            Return only the user-facing answer.
            </final_answer_phase>
            """;

    public record Result(ChatResponse response, List<ChatMessage> messages) {
        public Result {
            Objects.requireNonNull(response, "response");
            messages = List.copyOf(messages);
        }
    }

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final long timeoutSeconds;

    public FinalResponseGenerator(ChatModelProvider chatModelProvider, long timeoutSeconds) {
        Objects.requireNonNull(chatModelProvider, "chatModelProvider");
        this.chatModel = Objects.requireNonNull(chatModelProvider.chatModel(), "chatModel");
        this.streamingChatModel = chatModelProvider.streamingModel();
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

        return streamFinalAnswer(
                finalMessages(systemPrompt, planningMessages),
                requestParameters, listener, cancellationToken);
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
                    forward(listener, text);
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    responseFuture.complete(response);
                }

                @Override
                public void onError(Throwable error) {
                    responseFuture.completeExceptionally(error);
                }
            });

            return new Result(
                    responseFuture.get(timeoutSeconds, TimeUnit.SECONDS),
                    finalMessages);
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

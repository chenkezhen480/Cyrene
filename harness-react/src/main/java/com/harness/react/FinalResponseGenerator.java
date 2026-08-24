package com.harness.react;

import com.harness.core.model.CancellationToken;
import com.harness.core.model.FinalOutputContract;
import com.harness.core.exception.StructuredOutputException;
import com.harness.provider.ChatModelProvider;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;

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
            Do not mention READY_FOR_FINAL or the phase transition.
            </final_answer_phase>
            """;

    public record Result(ChatResponse response, List<ChatMessage> messages) {
        public Result {
            Objects.requireNonNull(response, "response");
            messages = List.copyOf(messages);
        }
    }

    private final ChatModelProvider chatModelProvider;
    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final long timeoutSeconds;

    public FinalResponseGenerator(ChatModelProvider chatModelProvider, long timeoutSeconds) {
        this.chatModelProvider = Objects.requireNonNull(chatModelProvider, "chatModelProvider");
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

        List<ChatMessage> finalMessages = finalMessages(systemPrompt, planningMessages);
        ChatRequest.Builder requestBuilder = ChatRequest.builder().messages(finalMessages);
        if (requestParameters != null) {
            requestBuilder.parameters(requestParameters);
        }

        CompletableFuture<ChatResponse> responseFuture = new CompletableFuture<>();
        streamingChatModel.chat(requestBuilder.build(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String text) {
                if (listener != null) {
                    listener.onToken(text);
                }
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

        if (cancellationToken != null) {
            cancellationToken.trackCurrentThread();
        }
        try {
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

    public Result generateBlocking(
            String systemPrompt,
            List<ChatMessage> planningMessages,
            ChatRequestParameters requestParameters,
            FinalOutputContract outputContract,
            CancellationToken cancellationToken
    ) {
        Objects.requireNonNull(outputContract, "outputContract");
        if (cancellationToken != null && cancellationToken.isCancelled()) {
            throw new java.util.concurrent.CancellationException(
                    "Final answer generation cancelled");
        }

        List<ChatMessage> finalMessages = finalMessages(systemPrompt, planningMessages);
        ChatRequestParameters responseFormatParameters = ChatRequestParameters.builder()
                .responseFormat(chatModelProvider.responseFormat(outputContract))
                .build();
        ChatRequestParameters finalParameters = requestParameters == null
                ? responseFormatParameters
                : requestParameters.overrideWith(responseFormatParameters);
        ChatRequest request = ChatRequest.builder()
                .messages(finalMessages)
                .parameters(finalParameters)
                .build();

        if (cancellationToken != null) {
            cancellationToken.trackCurrentThread();
        }
        ChatModel finalModel = outputContract instanceof FinalOutputContract.JsonSchema
                ? Objects.requireNonNull(
                        chatModelProvider.structuredChatModel(),
                        "structuredChatModel")
                : chatModel;
        try {
            ChatResponse response = finalModel.chat(request);
            validateStructuredResponse(response, outputContract);
            return new Result(response, finalMessages);
        } catch (ContentFilteredException e) {
            throw new StructuredOutputException(
                    StructuredOutputException.Code.STRUCTURED_OUTPUT_REFUSED,
                    "Model refused to produce structured output", java.util.Map.of(), e);
        } finally {
            if (cancellationToken != null) {
                cancellationToken.untrackCurrentThread();
            }
        }
    }

    private static void validateStructuredResponse(
            ChatResponse response, FinalOutputContract outputContract) {
        if (!(outputContract instanceof FinalOutputContract.JsonSchema)) {
            return;
        }
        FinishReason finishReason = response.metadata() != null
                ? response.metadata().finishReason()
                : null;
        if (finishReason == FinishReason.LENGTH) {
            throw new StructuredOutputException(
                    StructuredOutputException.Code.STRUCTURED_OUTPUT_TRUNCATED,
                    "Structured output was truncated by the model token limit");
        }
        if (finishReason == FinishReason.CONTENT_FILTER) {
            throw new StructuredOutputException(
                    StructuredOutputException.Code.STRUCTURED_OUTPUT_REFUSED,
                    "Model refused to produce structured output");
        }
    }

    private List<ChatMessage> finalMessages(
            String systemPrompt, List<ChatMessage> planningMessages) {
        List<ChatMessage> finalMessages = new ArrayList<>(planningMessages);
        finalMessages.set(0, SystemMessage.from(
                systemPrompt + "\n\n" + FINAL_ANSWER_INSTRUCTION));
        return finalMessages;
    }
}

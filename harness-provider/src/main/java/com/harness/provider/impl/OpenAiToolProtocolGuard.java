package com.harness.provider.impl;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Validates OpenAI-compatible tool responses at the provider boundary.
 *
 * <p>The ReAct loop must only see normalized structured tool calls or ordinary text.
 * Provider/tool-protocol dialect markup belongs here: if an upstream OpenAI-compatible
 * endpoint leaks model-native tool syntax into {@code content}, the response is rejected
 * instead of being interpreted by higher layers.</p>
 */
final class OpenAiToolProtocolGuard {

    private static final List<String> TOOL_PROTOCOL_MARKERS = List.of(
            "<｜DSML｜",
            "<｜｜DSML｜｜",
            "<|DSML|",
            "<||DSML||",
            "<tool_call",
            "<function_call");

    private OpenAiToolProtocolGuard() {
    }

    static ChatModel blocking(ChatModel delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                ChatResponse response = delegate.chat(request);
                validate(request, response);
                return response;
            }
        };
    }

    static StreamingChatModel streaming(StreamingChatModel delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                if (!hasTools(request)) {
                    delegate.chat(request, handler);
                    return;
                }
                delegate.chat(request, new GuardedHandler(request, handler));
            }
        };
    }

    static void validate(ChatRequest request, ChatResponse response) {
        if (!hasTools(request) || response == null || response.aiMessage() == null) {
            return;
        }

        boolean hasStructuredToolCalls = response.aiMessage().toolExecutionRequests() != null
                && !response.aiMessage().toolExecutionRequests().isEmpty();
        FinishReason finishReason = response.metadata() != null
                ? response.metadata().finishReason()
                : null;

        if (finishReason == FinishReason.TOOL_EXECUTION && !hasStructuredToolCalls) {
            throw malformed(
                    "finish_reason indicates tool execution but no structured tool call was parsed");
        }

        if (startsWithToolProtocolMarkup(response.aiMessage().text())) {
            throw malformed("tool-call protocol markup leaked into assistant content");
        }
    }

    static boolean startsWithToolProtocolMarkup(String text) {
        if (text == null) {
            return false;
        }
        String candidate = text.stripLeading();
        return TOOL_PROTOCOL_MARKERS.stream().anyMatch(candidate::startsWith);
    }

    static boolean couldBecomeToolProtocolMarkup(String text) {
        if (text == null) {
            return false;
        }
        String candidate = text.stripLeading();
        if (candidate.isEmpty()) {
            return true;
        }
        return TOOL_PROTOCOL_MARKERS.stream().anyMatch(marker -> marker.startsWith(candidate));
    }

    private static boolean hasTools(ChatRequest request) {
        return request != null
                && request.toolSpecifications() != null
                && !request.toolSpecifications().isEmpty();
    }

    private static MalformedToolResponseException malformed(String detail) {
        return new MalformedToolResponseException(detail);
    }

    private static final class GuardedHandler implements StreamingChatResponseHandler {

        private final ChatRequest request;
        private final StreamingChatResponseHandler downstream;
        private final StringBuilder heldText = new StringBuilder();
        private final List<Consumer<StreamingChatResponseHandler>> heldEvents = new ArrayList<>();
        private boolean textReleased;
        private boolean failed;

        private GuardedHandler(
                ChatRequest request,
                StreamingChatResponseHandler downstream
        ) {
            this.request = request;
            this.downstream = downstream;
        }

        @Override
        public void onPartialResponse(String partialResponse) {
            inspectText(partialResponse, handler -> handler.onPartialResponse(partialResponse), null);
        }

        @Override
        public void onPartialResponse(
                PartialResponse partialResponse,
                PartialResponseContext context
        ) {
            inspectText(
                    partialResponse.text(),
                    handler -> handler.onPartialResponse(partialResponse, context),
                    () -> context.streamingHandle().cancel());
        }

        @Override
        public void onPartialThinking(PartialThinking partialThinking) {
            if (!failed) {
                downstream.onPartialThinking(partialThinking);
            }
        }

        @Override
        public void onPartialThinking(
                PartialThinking partialThinking,
                PartialThinkingContext context
        ) {
            if (!failed) {
                downstream.onPartialThinking(partialThinking, context);
            }
        }

        @Override
        public void onPartialToolCall(PartialToolCall partialToolCall) {
            if (!failed) {
                downstream.onPartialToolCall(partialToolCall);
            }
        }

        @Override
        public void onPartialToolCall(
                PartialToolCall partialToolCall,
                PartialToolCallContext context
        ) {
            if (!failed) {
                downstream.onPartialToolCall(partialToolCall, context);
            }
        }

        @Override
        public void onCompleteToolCall(CompleteToolCall completeToolCall) {
            if (!failed) {
                downstream.onCompleteToolCall(completeToolCall);
            }
        }

        @Override
        public void onCompleteResponse(ChatResponse completeResponse) {
            if (failed) {
                return;
            }
            try {
                validate(request, completeResponse);
                releaseHeldText();
                downstream.onCompleteResponse(completeResponse);
            } catch (MalformedToolResponseException error) {
                fail(error, null);
            }
        }

        @Override
        public void onError(Throwable error) {
            if (!failed) {
                failed = true;
                downstream.onError(error);
            }
        }

        private void inspectText(
                String text,
                Consumer<StreamingChatResponseHandler> event,
                Runnable cancel
        ) {
            if (failed) {
                return;
            }
            if (textReleased) {
                event.accept(downstream);
                return;
            }

            heldText.append(text);
            heldEvents.add(event);
            String candidate = heldText.toString();

            if (startsWithToolProtocolMarkup(candidate)) {
                fail(malformed("tool-call protocol markup leaked into streamed assistant content"), cancel);
                return;
            }
            if (couldBecomeToolProtocolMarkup(candidate)) {
                return;
            }
            releaseHeldText();
        }

        private void releaseHeldText() {
            if (textReleased) {
                return;
            }
            textReleased = true;
            for (Consumer<StreamingChatResponseHandler> event : heldEvents) {
                event.accept(downstream);
            }
            heldEvents.clear();
            heldText.setLength(0);
        }

        private void fail(MalformedToolResponseException error, Runnable cancel) {
            if (failed) {
                return;
            }
            failed = true;
            heldEvents.clear();
            heldText.setLength(0);
            if (cancel != null) {
                cancel.run();
            }
            downstream.onError(error);
        }
    }
}

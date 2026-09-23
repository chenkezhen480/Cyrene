package com.harness.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.CancellationToken;
import com.harness.core.model.ModelUsage;
import com.harness.core.model.ThinkingLevel;
import com.harness.core.model.ToolCall;
import com.harness.core.model.ToolResult;
import com.harness.core.model.ToolSpec;
import com.harness.core.runtime.RunTrace;
import com.harness.provider.ChatModelProvider;
import com.harness.tool.Tool;
import com.harness.tool.ToolCatalog;
import com.harness.tool.ToolExecutor;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReActEngineTerminationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void dynamicKnowledgeIsAUserMessageAfterHistoryAndBeforeCurrentUser() {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                captured.set(request);
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("answer"))
                        .metadata(ChatResponseMetadata.builder().finishReason(FinishReason.STOP).build())
                        .build();
            }
        };

        new ReActEngine(provider(chatModel), catalog(), mock(ToolExecutor.class),
                null, null, 1).execute(new ReActRequest(
                "system",
                "current-user",
                List.of(UserMessage.from("history-user")),
                "<dynamic-knowledge-context>evidence</dynamic-knowledge-context>",
                RunTrace.noop(),
                null,
                null,
                ThinkingLevel.OFF,
                null));

        assertThat(captured.get().messages().stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .map(UserMessage::singleText)
                .toList())
                .containsExactly(
                        "history-user",
                        "<dynamic-knowledge-context>evidence</dynamic-knowledge-context>",
                        "current-user");
    }

    @Test
    void maxIterationsFailsClosedAndNormalizesMissingCallId() {
        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .id("")
                .name("test_tool")
                .arguments("{}")
                .build();
        AtomicInteger requests = new AtomicInteger();
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                requests.incrementAndGet();
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("planning", List.of(toolRequest)))
                        .metadata(ChatResponseMetadata.builder()
                                .finishReason(FinishReason.TOOL_EXECUTION)
                                .build())
                        .build();
            }
        };
        ToolExecutor executor = mock(ToolExecutor.class);
        AtomicReference<String> executedCallId = new AtomicReference<>();
        when(executor.executeAuthorized(any(), any(), isNull()))
                .thenAnswer(invocation -> {
                    ToolCall call = invocation.getArgument(0);
                    executedCallId.set(call.id());
                    return ToolResult.ok(
                            call.id(), call.toolName(), "raw tool output", 1,
                            com.harness.core.model.ResultStatus.AVAILABLE);
                });

        ReActEngine engine = new ReActEngine(
                provider(chatModel), catalog(), executor, null, null, 1);

        assertThatThrownBy(() -> engine.execute(new ReActRequest(
                "system", "use the tool", List.of(), RunTrace.noop(),
                null, null, ThinkingLevel.OFF, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max iterations");

        assertThat(requests).hasValue(1);
        assertThat(executedCallId.get()).isNotBlank();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sixthConsecutiveToolFailureStopsLoopWithoutSecondFinalCall(boolean streaming) {
        List<ChatRequest> requests = new ArrayList<>();
        AtomicInteger planningCalls = new AtomicInteger();
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                requests.add(request);
                if (request.parameters().toolSpecifications() == null
                        || request.parameters().toolSpecifications().isEmpty()) {
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from("final answer after hard limit"))
                            .metadata(ChatResponseMetadata.builder()
                                    .finishReason(FinishReason.STOP)
                                    .build())
                            .build();
                }
                int callNumber = planningCalls.incrementAndGet();
                ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                        .id("call-" + callNumber)
                        .name("test_tool")
                        .arguments("{\"invalid\":true}")
                        .build();
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("planning", List.of(toolRequest)))
                        .metadata(ChatResponseMetadata.builder()
                                .finishReason(FinishReason.TOOL_EXECUTION)
                                .build())
                        .build();
            }
        };
        ToolExecutor executor = mock(ToolExecutor.class);
        AtomicInteger executions = new AtomicInteger();
        when(executor.executeAuthorized(any(), any(), isNull()))
                .thenAnswer(invocation -> {
                    ToolCall call = invocation.getArgument(0);
                    executions.incrementAndGet();
                    return ToolResult.fail(
                            call.id(), call.toolName(), "invalid graph parameters", 1);
                });

        ChatModelProvider provider = provider(chatModel);
        when(provider.streamingModel()).thenReturn(new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                ChatResponse response = chatModel.chat(request);
                handler.onPartialResponse(response.aiMessage().text());
                handler.onCompleteResponse(response);
            }
        });
        List<String> tokens = new ArrayList<>();
        ReActListener listener = new ReActListener() {
            @Override public void onStep(com.harness.core.model.ReActStep step) {}
            @Override public void onToken(String token) { tokens.add(token); }
        };
        ReActEngine engine = new ReActEngine(provider, catalog(), executor, null, null, 10);
        ReActRequest request = new ReActRequest(
                "system", "must use the tool", List.of(), RunTrace.noop(),
                listener, null, ThinkingLevel.OFF, null);
        ReActResult result = streaming ? engine.streamExecute(request) : engine.execute(request);

        assertThat(executions).hasValue(6);
        assertThat(planningCalls).hasValue(6);
        assertThat(requests).hasSize(6);
        assertThat(requests).allSatisfy(chatRequest ->
                assertThat(chatRequest.parameters().toolSpecifications()).isNotEmpty());
        assertThat(result.output()).isNotBlank();
        assertThat(result.loopStats().outcome()).isEqualTo("tool_failure_limit");
        if (streaming) {
            assertThat(tokens).containsExactly(
                    "planning", "planning", "planning",
                    "planning", "planning", "planning");
        }
        assertThat(result.steps().get(5).inspection().status())
                .isEqualTo(com.harness.core.model.ReActStep.InspectionResult.InspectionStatus.LOOP_DETECTED);
    }

    @Test
    void cancelledRequestThrowsInsteadOfReturningDoneResult() {
        ChatModel chatModel = mock(ChatModel.class);
        CancellationToken cancellationToken = new CancellationToken();
        cancellationToken.cancel();

        ReActEngine engine = new ReActEngine(
                provider(chatModel), catalog(), mock(ToolExecutor.class),
                null, null, 2);

        assertThatThrownBy(() -> engine.execute(new ReActRequest(
                "system", "cancel", List.of(), RunTrace.noop(),
                null, cancellationToken, ThinkingLevel.OFF, null)))
                .isInstanceOf(CancellationException.class);
        verify(chatModel, never()).chat(any(ChatRequest.class));
    }

    @Test
    void cancellationTargetsOnlyTheSelectedGroupedDelegate() {
        var browser = mock(com.harness.tool.CancellableTool.class);
        var search = mock(com.harness.tool.CancellableTool.class);
        when(browser.spec()).thenReturn(new ToolSpec("browser_control", "Browser",
                MAPPER.createObjectNode().put("type", "object")));
        when(search.spec()).thenReturn(new ToolSpec("web_search", "Search",
                MAPPER.createObjectNode().put("type", "object")));
        var registry = new com.harness.tool.ToolRegistry();
        registry.register(new com.harness.tool.ToolGroup("web", "Web access",
                java.util.Map.of("browser", browser, "search", search), List.of()));
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(
                ToolExecutionRequest.builder().id("call-1").name("web")
                        .arguments("{\"action\":\"browser\",\"input\":{\"action\":\"observe\"}}")
                        .build()))
                .metadata(ChatResponseMetadata.builder()
                        .finishReason(FinishReason.TOOL_EXECUTION)
                        .build())
                .build());
        var token = new CancellationToken();
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.executeAuthorized(any(), any(), isNull())).thenAnswer(invocation -> {
            token.cancel();
            ToolCall call = invocation.getArgument(0);
            return ToolResult.fail(call.id(), call.toolName(), "Cancelled", 0);
        });
        try {
            assertThatThrownBy(() -> new ReActEngine(provider(chatModel), registry.snapshot(),
                    executor, null, null, 2).execute(new ReActRequest(
                    "system", "browse", List.of(), RunTrace.noop(), null, token, ThinkingLevel.OFF, null)))
                    .isInstanceOf(CancellationException.class);
            verify(browser).cancel();
            verify(search, never()).cancel();
        } finally {
            Thread.interrupted();
        }
    }

    private static ChatModelProvider provider(ChatModel chatModel) {
        ChatModelProvider provider = mock(ChatModelProvider.class);
        when(provider.chatModel()).thenReturn(chatModel);
        when(provider.planningRequestParameters(nullable(ThinkingLevel.class), anyList()))
                .thenCallRealMethod();
        when(provider.modelUsage(any(), anyLong())).thenAnswer(invocation ->
                new ModelUsage(null, null, null, null, null,
                        invocation.getArgument(1), null, null));
        return provider;
    }

    private static ToolCatalog catalog() {
        ToolSpec toolSpec = new ToolSpec(
                "test_tool",
                "Returns a deterministic result",
                MAPPER.createObjectNode().put("type", "object"));
        Tool tool = mock(Tool.class);
        when(tool.spec()).thenReturn(toolSpec);
        ToolCatalog catalog = mock(ToolCatalog.class);
        when(catalog.getAll()).thenReturn(List.of(toolSpec));
        when(catalog.get("test_tool")).thenReturn(tool);
        when(catalog.size()).thenReturn(1);
        when(catalog.version()).thenReturn(1L);
        return catalog;
    }
}

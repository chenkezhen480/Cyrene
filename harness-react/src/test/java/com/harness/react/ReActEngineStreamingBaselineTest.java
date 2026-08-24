package com.harness.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.ModelUsage;
import com.harness.core.model.ToolCall;
import com.harness.core.model.ToolCallStatus;
import com.harness.core.model.ToolResult;
import com.harness.core.model.ToolSpec;
import com.harness.core.runtime.RunTrace;
import com.harness.provider.ChatModelProvider;
import com.harness.tool.Tool;
import com.harness.tool.ToolCatalog;
import com.harness.tool.ToolExecutor;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReActEngineStreamingBaselineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void streamExecute_withVisibleTools_streamsOnlyFinalAnswerAndKeepsCallIds() {
        ToolExecutionRequest firstToolRequest = ToolExecutionRequest.builder()
                .id("call-1")
                .name("test_tool")
                .arguments("{}")
                .build();
        ToolExecutionRequest secondToolRequest = ToolExecutionRequest.builder()
                .id("call-2")
                .name("test_tool")
                .arguments("{\"sequence\":2}")
                .build();
        StreamingChatModel streamingModel = scriptedStreamingModel(
                new ScriptedResponse(
                        "I will call test_tool now.",
                        ChatResponse.builder()
                                .aiMessage(AiMessage.from(
                                        "planning",
                                        List.of(firstToolRequest, secondToolRequest)))
                                .build()),
                new ScriptedResponse(
                        "READY_FOR_FINAL",
                        ChatResponse.builder()
                                .aiMessage(AiMessage.from("READY_FOR_FINAL"))
                                .build()),
                new ScriptedResponse(
                        "The final answer.",
                        ChatResponse.builder()
                                .aiMessage(AiMessage.from("The final answer."))
                                .build()));

        ChatModelProvider provider = mock(ChatModelProvider.class);
        when(provider.chatModel()).thenReturn(mock(ChatModel.class));
        when(provider.streamingModel()).thenReturn(streamingModel);
        when(provider.planningRequestParameters(nullable(Boolean.class), anyList()))
                .thenCallRealMethod();
        when(provider.modelUsage(any(), anyLong())).thenAnswer(invocation ->
                new ModelUsage(null, null, null, null, null,
                        invocation.getArgument(1), null, null));

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

        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.executeAuthorized(any(), any(), isNull()))
                .thenAnswer(invocation -> {
                    ToolCall toolCall = invocation.getArgument(0);
                    return ToolResult.ok(
                            toolCall.id(),
                            toolCall.toolName(),
                            "tool result",
                            1,
                            ToolResult.ResultStatus.SUCCESS);
                });

        List<String> visibleTokens = new ArrayList<>();
        List<String> toolEvents = new ArrayList<>();
        ReActListener listener = new ReActListener() {
            @Override
            public void onStep(com.harness.core.model.ReActStep step) {
            }

            @Override
            public void onToken(String token) {
                visibleTokens.add(token);
            }

            @Override
            public void onToolCallCreated(
                    String toolCallId, String toolName, String arguments) {
                toolEvents.add("CREATED:" + toolCallId);
            }

            @Override
            public void onToolCallStart(
                    String toolCallId, String toolName, String arguments) {
                toolEvents.add("RUNNING:" + toolCallId);
            }

            @Override
            public void onToolCallDone(
                    String toolCallId,
                    String toolName,
                    ToolCallStatus status,
                    long durationMs,
                    String errorSummary) {
                toolEvents.add(status.name() + ":" + toolCallId);
            }
        };
        ReActEngine engine = new ReActEngine(provider, catalog, executor, null, null, 3);
        ReActRequest request = new ReActRequest(
                "system",
                "use the tool",
                List.of(),
                RunTrace.noop(),
                listener,
                null,
                false,
                null);

        ReActResult result = engine.streamExecute(request);

        assertThat(result.output()).isEqualTo("The final answer.");
        assertThat(visibleTokens).containsExactly("The final answer.");
        assertThat(toolEvents).containsExactly(
                "CREATED:call-1",
                "CREATED:call-2",
                "RUNNING:call-1",
                "SUCCEEDED:call-1",
                "RUNNING:call-2",
                "SUCCEEDED:call-2");
    }

    private static StreamingChatModel scriptedStreamingModel(ScriptedResponse... responses) {
        AtomicInteger nextResponse = new AtomicInteger();
        return new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                ScriptedResponse response = responses[nextResponse.getAndIncrement()];
                handler.onPartialResponse(response.partialText());
                handler.onCompleteResponse(response.response());
            }
        };
    }

    private record ScriptedResponse(String partialText, ChatResponse response) {
    }
}

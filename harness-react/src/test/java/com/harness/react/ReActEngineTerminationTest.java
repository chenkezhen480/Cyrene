package com.harness.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.CancellationToken;
import com.harness.core.model.ModelUsage;
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
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

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
    void maxIterationsGeneratesToolFreeFinalAnswerAndNormalizesMissingCallId() {
        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .id("")
                .name("test_tool")
                .arguments("{}")
                .build();
        List<ChatRequest> requests = new ArrayList<>();
        AtomicInteger responseIndex = new AtomicInteger();
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                requests.add(request);
                return responseIndex.getAndIncrement() == 0
                        ? ChatResponse.builder()
                                .aiMessage(AiMessage.from("planning", List.of(toolRequest)))
                                .build()
                        : ChatResponse.builder()
                                .aiMessage(AiMessage.from("final answer after limit"))
                                .build();
            }
        };
        ChatModelProvider provider = provider(chatModel);
        ToolCatalog catalog = catalog();
        ToolExecutor executor = mock(ToolExecutor.class);
        AtomicReference<String> executedCallId = new AtomicReference<>();
        when(executor.executeAuthorized(any(), any(), isNull()))
                .thenAnswer(invocation -> {
                    ToolCall call = invocation.getArgument(0);
                    executedCallId.set(call.id());
                    return ToolResult.ok(
                            call.id(), call.toolName(), "raw tool output", 1,
                            ToolResult.ResultStatus.SUCCESS);
                });

        ReActResult result = new ReActEngine(
                provider, catalog, executor, null, null, 1)
                .execute(new ReActRequest(
                        "system", "use the tool", List.of(), RunTrace.noop(),
                        null, null, false, null));

        assertThat(result.output()).isEqualTo("final answer after limit");
        assertThat(result.output()).isNotEqualTo("raw tool output");
        assertThat(result.loopStats().outcome()).isEqualTo("max_iterations");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).parameters().toolSpecifications()).isEmpty();
        assertThat(executedCallId.get()).isNotBlank();

        AiMessage normalizedPlanningMessage = requests.get(1).messages().stream()
                .filter(AiMessage.class::isInstance)
                .map(AiMessage.class::cast)
                .findFirst()
                .orElseThrow();
        ToolExecutionResultMessage toolResultMessage = requests.get(1).messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(normalizedPlanningMessage.toolExecutionRequests().get(0).id())
                .isEqualTo(executedCallId.get());
        assertThat(toolResultMessage.id()).isEqualTo(executedCallId.get());
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
                null, cancellationToken, false, null)))
                .isInstanceOf(CancellationException.class);
        verify(chatModel, never()).chat(any(ChatRequest.class));
    }

    private static ChatModelProvider provider(ChatModel chatModel) {
        ChatModelProvider provider = mock(ChatModelProvider.class);
        when(provider.chatModel()).thenReturn(chatModel);
        when(provider.planningRequestParameters(nullable(Boolean.class), anyList()))
                .thenCallRealMethod();
        when(provider.responseFormat(any())).thenCallRealMethod();
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

package com.harness.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.FinalOutputContract;
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
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
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

class ReActEngineStructuredOutputTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void appliesSchemaOnlyToFinalNoToolCallAfterToolExecution() throws Exception {
        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .id("call-structured-1")
                .name("customer_lookup")
                .arguments("{\"customerId\":\"c-1\"}")
                .build();
        List<ChatRequest> requests = new ArrayList<>();
        AtomicInteger responseIndex = new AtomicInteger();
        List<ChatResponse> responses = List.of(
                ChatResponse.builder()
                        .aiMessage(AiMessage.from("planning", List.of(toolRequest)))
                        .build(),
                ChatResponse.builder()
                        .aiMessage(AiMessage.from("READY_FOR_FINAL"))
                        .build(),
                ChatResponse.builder()
                        .aiMessage(AiMessage.from(
                                "{\"eligible\":true,\"reason\":\"qualified\"}"))
                        .build());
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                requests.add(request);
                return responses.get(responseIndex.getAndIncrement());
            }
        };

        ChatModelProvider provider = mock(ChatModelProvider.class);
        when(provider.chatModel()).thenReturn(chatModel);
        when(provider.structuredChatModel()).thenReturn(chatModel);
        when(provider.planningRequestParameters(nullable(Boolean.class), anyList()))
                .thenCallRealMethod();
        when(provider.responseFormat(any())).thenReturn(ResponseFormat.JSON);
        when(provider.modelUsage(any(), anyLong())).thenAnswer(invocation ->
                new ModelUsage(null, null, null, null, null,
                        invocation.getArgument(1), null, null));

        ToolSpec toolSpec = new ToolSpec(
                "customer_lookup",
                "Looks up a customer",
                MAPPER.readTree("""
                        {
                          "type":"object",
                          "properties":{"customerId":{"type":"string"}},
                          "required":["customerId"],
                          "additionalProperties":false
                        }
                        """));
        Tool tool = mock(Tool.class);
        when(tool.spec()).thenReturn(toolSpec);
        ToolCatalog catalog = mock(ToolCatalog.class);
        when(catalog.getAll()).thenReturn(List.of(toolSpec));
        when(catalog.get("customer_lookup")).thenReturn(tool);
        when(catalog.size()).thenReturn(1);

        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.executeAuthorized(any(), any(), isNull()))
                .thenAnswer(invocation -> {
                    ToolCall call = invocation.getArgument(0);
                    return ToolResult.ok(
                            call.id(), call.toolName(),
                            "{\"annualAmount\":120000}", 1,
                            ToolResult.ResultStatus.SUCCESS);
                });

        var outputSchema = MAPPER.readTree("""
                {
                  "type":"object",
                  "properties":{
                    "eligible":{"type":"boolean"},
                    "reason":{"type":"string"}
                  },
                  "required":["eligible","reason"],
                  "additionalProperties":false
                }
                """);
        ReActEngine engine = new ReActEngine(
                provider, catalog, executor, null, null, 4);
        ReActResult result = engine.execute(new ReActRequest(
                "system",
                "check customer c-1",
                List.of(),
                RunTrace.noop(),
                null,
                null,
                false,
                null,
                new FinalOutputContract.JsonSchema(
                        "customerDecision", outputSchema, true)));

        assertThat(result.output())
                .isEqualTo("{\"eligible\":true,\"reason\":\"qualified\"}");
        assertThat(requests).hasSize(3);
        assertThat(requests.get(0).parameters().responseFormat()).isNull();
        assertThat(requests.get(0).parameters().toolSpecifications()).hasSize(1);
        assertThat(requests.get(1).parameters().responseFormat()).isNull();
        assertThat(requests.get(1).parameters().toolSpecifications()).hasSize(1);
        assertThat(requests.get(2).parameters().responseFormat())
                .isEqualTo(ResponseFormat.JSON);
        assertThat(requests.get(2).parameters().toolSpecifications()).isEmpty();
    }
}

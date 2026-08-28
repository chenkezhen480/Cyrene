package com.harness.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.harness.core.exception.StructuredOutputException;
import com.harness.core.model.FinalOutputContract;
import com.harness.core.model.ModelUsage;
import com.harness.core.model.ReActStep;
import com.harness.core.model.ToolCall;
import com.harness.core.model.ToolResult;
import com.harness.core.model.ToolOutput;
import com.harness.core.model.ToolSpec;
import com.harness.core.runtime.RunTrace;
import com.harness.provider.ChatModelProvider;
import com.harness.tool.Tool;
import com.harness.tool.ToolCatalog;
import com.harness.tool.ToolExecutor;
import com.harness.tool.builtin.StructuredOutputTool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
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
import static org.mockito.Mockito.verifyNoInteractions;
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
                        .aiMessage(AiMessage.from("", List.of(
                                ToolExecutionRequest.builder()
                                        .id("call-structured-2")
                                        .name(StructuredOutputTool.TOOL_NAME)
                                        .arguments("{\"eligible\":true,\"reason\":\"qualified\"}")
                                        .build())))
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
        when(provider.planningRequestParameters(nullable(Boolean.class), anyList()))
                .thenCallRealMethod();
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
        StructuredOutputTool outputTool = StructuredOutputTool.terminal(
                new FinalOutputContract.JsonSchema(
                        "customerDecision", outputSchema, true));
        ToolCatalog catalog = mock(ToolCatalog.class);
        when(catalog.getAll()).thenReturn(List.of(toolSpec, outputTool.spec()));
        when(catalog.get("customer_lookup")).thenReturn(tool);
        when(catalog.get(StructuredOutputTool.TOOL_NAME)).thenReturn(outputTool);
        when(catalog.size()).thenReturn(2);

        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.executeAuthorized(any(), any(), isNull()))
                .thenAnswer(invocation -> {
                    ToolCall call = invocation.getArgument(0);
                    if (StructuredOutputTool.TOOL_NAME.equals(call.toolName())) {
                        return ToolResult.ok(
                                call.id(), call.toolName(), ToolOutput.json(call.arguments()), 1,
                                ToolResult.ResultStatus.SUCCESS);
                    }
                    return ToolResult.ok(
                            call.id(), call.toolName(),
                            "{\"annualAmount\":120000}", 1,
                            ToolResult.ResultStatus.SUCCESS);
                });

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
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).parameters().responseFormat()).isNull();
        assertThat(requests.get(0).parameters().toolSpecifications()).hasSize(2);
        assertThat(requests.get(1).parameters().responseFormat()).isNull();
        assertThat(requests.get(1).parameters().toolSpecifications()).hasSize(2);
    }

    @Test
    void emitsStructuredBlockAndContinuesNormalChat() throws Exception {
        ToolExecutionRequest structuredRequest = ToolExecutionRequest.builder()
                .id("call-chat-structured")
                .name(StructuredOutputTool.TOOL_NAME)
                .arguments("{\"items\":[{\"id\":\"c-1\",\"score\":0.91}]}")
                .build();
        AtomicInteger responseIndex = new AtomicInteger();
        List<ChatResponse> responses = List.of(
                ChatResponse.builder()
                        .aiMessage(AiMessage.from("", List.of(structuredRequest)))
                        .build(),
                ChatResponse.builder()
                        .aiMessage(AiMessage.from("已整理为结构化结果。"))
                        .build());
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return responses.get(responseIndex.getAndIncrement());
            }
        };
        ChatModelProvider provider = configuredProvider(chatModel);

        StructuredOutputTool outputTool = StructuredOutputTool.chatBlock();
        ToolCatalog catalog = mock(ToolCatalog.class);
        when(catalog.getAll()).thenReturn(List.of(outputTool.spec()));
        when(catalog.get(StructuredOutputTool.TOOL_NAME)).thenReturn(outputTool);
        when(catalog.size()).thenReturn(1);

        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.executeAuthorized(any(), any(), isNull()))
                .thenAnswer(invocation -> {
                    ToolCall call = invocation.getArgument(0);
                    return ToolResult.ok(
                            call.id(), call.toolName(), ToolOutput.json(call.arguments()), 1,
                            ToolResult.ResultStatus.SUCCESS);
                });
        AtomicReference<JsonNode> structuredData = new AtomicReference<>();
        ReActListener listener = new ReActListener() {
            @Override
            public void onStep(ReActStep step) {
            }

            @Override
            public void onStructuredOutput(JsonNode data) {
                structuredData.set(data);
            }
        };

        ReActEngine engine = new ReActEngine(
                provider, catalog, executor, null, null, 4);
        ReActResult result = engine.execute(new ReActRequest(
                "system",
                "返回客户匹配结果",
                List.of(),
                RunTrace.noop(),
                listener,
                null,
                false,
                null,
                new FinalOutputContract.Text()));

        assertThat(result.output()).isEqualTo("已整理为结构化结果。");
        assertThat(structuredData.get()).isEqualTo(MAPPER.readTree(
                "{\"items\":[{\"id\":\"c-1\",\"score\":0.91}]}"));
        assertThat(responseIndex).hasValue(2);
    }

    @Test
    void rejectsTerminalSubmissionMixedWithOtherToolsBeforeExecution() throws Exception {
        var outputSchema = MAPPER.readTree("""
                {
                  "type":"object",
                  "properties":{"eligible":{"type":"boolean"}},
                  "required":["eligible"],
                  "additionalProperties":false
                }
                """);
        StructuredOutputTool outputTool = StructuredOutputTool.terminal(
                new FinalOutputContract.JsonSchema("customerDecision", outputSchema, true));
        ToolSpec lookupSpec = new ToolSpec(
                "customer_lookup",
                "Looks up a customer",
                MAPPER.readTree("{\"type\":\"object\"}"));
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("", List.of(
                                ToolExecutionRequest.builder()
                                        .id("call-lookup")
                                        .name("customer_lookup")
                                        .arguments("{}")
                                        .build(),
                                ToolExecutionRequest.builder()
                                        .id("call-submit")
                                        .name(StructuredOutputTool.TOOL_NAME)
                                        .arguments("{\"eligible\":true}")
                                        .build())))
                        .build();
            }
        };
        ChatModelProvider provider = configuredProvider(chatModel);
        ToolCatalog catalog = mock(ToolCatalog.class);
        when(catalog.getAll()).thenReturn(List.of(lookupSpec, outputTool.spec()));
        when(catalog.size()).thenReturn(2);
        ToolExecutor executor = mock(ToolExecutor.class);

        ReActEngine engine = new ReActEngine(
                provider, catalog, executor, null, null, 2);

        assertThatThrownBy(() -> engine.execute(new ReActRequest(
                "system",
                "check customer",
                List.of(),
                RunTrace.noop(),
                null,
                null,
                false,
                null,
                new FinalOutputContract.JsonSchema(
                        "customerDecision", outputSchema, true))))
                .isInstanceOf(StructuredOutputException.class)
                .hasMessageContaining("only tool call");
        verifyNoInteractions(executor);
    }

    private static ChatModelProvider configuredProvider(ChatModel chatModel) {
        ChatModelProvider provider = mock(ChatModelProvider.class);
        when(provider.chatModel()).thenReturn(chatModel);
        when(provider.planningRequestParameters(nullable(Boolean.class), anyList()))
                .thenCallRealMethod();
        when(provider.modelUsage(any(), anyLong())).thenAnswer(invocation ->
                new ModelUsage(null, null, null, null, null,
                        invocation.getArgument(1), null, null));
        return provider;
    }
}

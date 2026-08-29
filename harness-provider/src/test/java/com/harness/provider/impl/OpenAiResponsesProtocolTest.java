package com.harness.provider.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.modelconfig.ModelConfig;
import com.harness.core.modelconfig.ModelConfigKey;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiResponsesProtocolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sendsCompleteStatelessHistoryAndToolResultsToResponsesEndpoint()
            throws Exception {
        List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> handleResponse(exchange, requests));
        server.start();
        try {
            ModelConfig config = ModelConfig.of(Map.of(
                    ModelConfigKey.CHAT_API_KEY, "test-key",
                    ModelConfigKey.CHAT_BASE_URL,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    ModelConfigKey.CHAT_MODEL, "test-model",
                    ModelConfigKey.CHAT_MAX_TOKENS, "512",
                    ModelConfigKey.CHAT_TEMPERATURE, "0.2",
                    ModelConfigKey.CHAT_THINKING, "false",
                    ModelConfigKey.CHAT_TIMEOUT_SECONDS, "2"
            ));
            OpenAiChatModelProvider provider =
                    new OpenAiChatModelProvider(config, OpenAiChatApiFormat.RESPONSES);
            ChatModel model = provider.createRawChatModel();

            ToolExecutionRequest toolCall = ToolExecutionRequest.builder()
                    .id("call-person-1")
                    .name("lookupPerson")
                    .arguments("{\"personId\":\"p-1\"}")
                    .build();
            model.chat(ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from("Use local audit history."),
                            UserMessage.from("Find person p-1"),
                            AiMessage.from("", List.of(toolCall)),
                            ToolExecutionResultMessage.from(
                                    toolCall,
                                    "{\"name\":\"Cyrene\"}")))
                    .build());

            StreamingChatModel streamingModel = provider.streamingModel();
            List<String> partialResponses = new CopyOnWriteArrayList<>();
            CompletableFuture<ChatResponse> completedResponse = new CompletableFuture<>();
            streamingModel.chat(
                    ChatRequest.builder()
                            .messages(List.of(UserMessage.from("Stream a short answer")))
                            .build(),
                    new StreamingChatResponseHandler() {
                        @Override
                        public void onPartialResponse(String partialResponse) {
                            partialResponses.add(partialResponse);
                        }

                        @Override
                        public void onCompleteResponse(ChatResponse completeResponse) {
                            completedResponse.complete(completeResponse);
                        }

                        @Override
                        public void onError(Throwable error) {
                            completedResponse.completeExceptionally(error);
                        }
                    });
            ChatResponse streamResponse = completedResponse.get(2, TimeUnit.SECONDS);

            CompletableFuture<ChatResponse> completedToolResponse = new CompletableFuture<>();
            streamingModel.chat(
                    ChatRequest.builder()
                            .messages(List.of(UserMessage.from("Stream a tool call")))
                            .parameters(provider.planningRequestParameters(
                                    null,
                                    List.of(ToolSpecification.builder()
                                            .name("lookupPerson")
                                            .description("Looks up a person")
                                            .parameters(JsonObjectSchema.builder()
                                                    .additionalProperties(false)
                                                    .build())
                                            .build())))
                            .build(),
                    new StreamingChatResponseHandler() {
                        @Override
                        public void onPartialResponse(String partialResponse) {
                        }

                        @Override
                        public void onCompleteResponse(ChatResponse completeResponse) {
                            completedToolResponse.complete(completeResponse);
                        }

                        @Override
                        public void onError(Throwable error) {
                            completedToolResponse.completeExceptionally(error);
                        }
                    });
            ChatResponse toolStreamResponse = completedToolResponse.get(2, TimeUnit.SECONDS);

            assertThat(requests).hasSize(3);
            assertThat(requests).allSatisfy(request -> {
                assertThat(request.path()).isEqualTo("/v1/responses");
                assertThat(request.body().path("store").asBoolean()).isFalse();
                assertThat(request.body().has("previous_response_id")).isFalse();
            });

            JsonNode historyInput = requests.get(0).body().path("input");
            assertThat(historyInput.toString())
                    .contains("Use local audit history.")
                    .contains("Find person p-1")
                    .contains("function_call")
                    .contains("call-person-1")
                    .contains("function_call_output")
                    .contains("Cyrene");

            assertThat(requests.get(1).body().path("stream").asBoolean()).isTrue();
            assertThat(partialResponses).containsExactly("Cyrene");
            assertThat(streamResponse.aiMessage().text()).isEqualTo("Cyrene");
            assertThat(streamResponse.metadata().tokenUsage().inputTokenCount()).isEqualTo(10);
            assertThat(requests.get(2).body().path("tools").get(0).path("name").asText())
                    .isEqualTo("lookupPerson");
            assertThat(toolStreamResponse.aiMessage().toolExecutionRequests()).singleElement()
                    .satisfies(toolRequest -> {
                        assertThat(toolRequest.id()).isEqualTo("call-stream-1");
                        assertThat(toolRequest.name()).isEqualTo("lookupPerson");
                        assertThat(toolRequest.arguments()).isEqualTo("{\"personId\":\"p-1\"}");
                    });
        } finally {
            server.stop(0);
        }
    }

    private static void handleResponse(
            HttpExchange exchange,
            List<CapturedRequest> requests
    ) throws IOException {
        byte[] requestBytes = exchange.getRequestBody().readAllBytes();
        JsonNode requestBody = MAPPER.readTree(requestBytes);
        requests.add(new CapturedRequest(exchange.getRequestURI().getPath(), requestBody));
        String responseJson = """
                {
                  "id":"resp-test",
                  "object":"response",
                  "created_at":1,
                  "status":"completed",
                  "model":"test-model",
                  "output":[{
                    "id":"msg-test",
                    "type":"message",
                    "status":"completed",
                    "role":"assistant",
                    "content":[{
                      "type":"output_text",
                      "text":"Cyrene",
                      "annotations":[]
                    }]
                  }],
                  "usage":{
                    "input_tokens":10,
                    "input_tokens_details":{"cached_tokens":4},
                    "output_tokens":3,
                    "output_tokens_details":{"reasoning_tokens":1},
                    "total_tokens":13
                  }
                }
                """;
        responseJson = MAPPER.writeValueAsString(MAPPER.readTree(responseJson));
        boolean streaming = requestBody.path("stream").asBoolean(false);
        boolean toolStreaming = streaming && requestBody.toString().contains("Stream a tool call");
        String responseBody;
        if (toolStreaming) {
            String toolResponseJson = MAPPER.writeValueAsString(MAPPER.readTree("""
                    {
                      "id":"resp-tool-test",
                      "object":"response",
                      "created_at":1,
                      "status":"completed",
                      "model":"test-model",
                      "output":[{
                        "id":"fc-stream-1",
                        "type":"function_call",
                        "status":"completed",
                        "call_id":"call-stream-1",
                        "name":"lookupPerson",
                        "arguments":"{\\\"personId\\\":\\\"p-1\\\"}"
                      }],
                      "usage":{"input_tokens":10,"output_tokens":3,"total_tokens":13}
                    }
                    """));
            responseBody = "data: {\"type\":\"response.output_item.added\","
                    + "\"output_index\":0,\"item\":{\"id\":\"fc-stream-1\","
                    + "\"type\":\"function_call\",\"status\":\"in_progress\","
                    + "\"call_id\":\"call-stream-1\",\"name\":\"lookupPerson\","
                    + "\"arguments\":\"\"}}\n\n"
                    + "data: {\"type\":\"response.function_call_arguments.delta\","
                    + "\"item_id\":\"fc-stream-1\",\"output_index\":0,"
                    + "\"delta\":\"{\\\"personId\\\":\\\"p-1\\\"}\"}\n\n"
                    + "data: {\"type\":\"response.function_call_arguments.done\","
                    + "\"item_id\":\"fc-stream-1\",\"output_index\":0,"
                    + "\"arguments\":\"{\\\"personId\\\":\\\"p-1\\\"}\"}\n\n"
                    + "data: {\"type\":\"response.completed\",\"response\":"
                    + toolResponseJson + "}\n\n"
                    + "data: [DONE]\n\n";
        } else if (streaming) {
            responseBody = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Cyrene\"}\n\n"
                    + "data: {\"type\":\"response.completed\",\"response\":"
                    + responseJson + "}\n\n"
                    + "data: [DONE]\n\n";
        } else {
            responseBody = responseJson;
        }
        byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type", streaming ? "text/event-stream" : "application/json");
        exchange.sendResponseHeaders(200, responseBytes.length);
        exchange.getResponseBody().write(responseBytes);
        exchange.close();
    }

    private record CapturedRequest(String path, JsonNode body) {
    }
}

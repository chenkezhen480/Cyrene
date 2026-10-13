package experiment;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AlibabaRuntimeTest {
    @Test void nativeReactCallsToolWithHiddenIdentityAndRecordsWireUsage() throws Exception {
        var mapper = new ObjectMapper();
        var modelRequests = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var businessHeaders = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String text;
            if (exchange.getRequestURI().getPath().equals("/v1/chat/completions")) {
                var body = mapper.readTree(exchange.getRequestBody()); modelRequests.add(body);
                String message = modelRequests.size() == 1
                        ? "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"getCustomer\",\"arguments\":\"{\\\"id\\\":\\\"C103\\\"}\"}}]}"
                        : "{\"role\":\"assistant\",\"content\":\"蓝榫物流C36 platinum 2026-11-15\"}";
                text = "{\"id\":\"fake-" + modelRequests.size() + "\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"synthetic\",\"choices\":[{\"index\":0,\"message\":" + message + ",\"finish_reason\":\"" + (modelRequests.size() == 1 ? "tool_calls" : "stop") + "\"}],\"usage\":{\"prompt_tokens\":23,\"completion_tokens\":7,\"total_tokens\":30}}";
            } else {
                businessHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
                text = "{\"id\":\"C103\",\"name\":\"蓝榫物流C36\",\"tier\":\"platinum\",\"contractExpiresAt\":\"2026-11-15\"}";
            }
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length); exchange.getResponseBody().write(payload); exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var settings = Map.of("chat.provider", "openai", "chat.baseUrl", base + "/v1", "chat.apiKey", "model-secret",
                    "chat.model", "synthetic", "chat.temperature", "0.7", "chat.maxTokens", "16384");
            var tools = new CrmTools(base);
            var model = new MeasuredModel(AlibabaRuntime.model(settings));
            var runtime = new AlibabaRuntime(model, tools, "Read business records using tools only.", 10);
            assertEquals("蓝榫物流C36 platinum 2026-11-15", runtime.call("Read C103", "owner-secret", UUID.randomUUID().toString()));
            assertEquals(List.of("Bearer owner-secret"), businessHeaders);
            assertEquals(2, model.calls().size());
            assertEquals(23, model.metadata().path("llmUsageCall1InputTokens").asInt());
            for (var request : modelRequests) {
                assertFalse(request.toString().contains("owner-secret"));
                assertFalse(request.toString().contains("model-secret"));
                assertFalse(request.path("stream").asBoolean());
                assertEquals("none", request.path("reasoning_effort").asText());
                assertEquals(0.7, request.path("temperature").asDouble());
                assertEquals(16384, request.path("max_tokens").asInt());
            }
        } finally { server.stop(0); }
    }
}

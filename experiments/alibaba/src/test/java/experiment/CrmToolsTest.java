package experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import java.net.InetSocketAddress;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CrmToolsTest {
    private HttpServer server;
    private final List<Map<String, String>> requests = new ArrayList<>();
    private CrmTools tools;
    private final ToolContext context = new ToolContext(Map.of("fixtureToken", "trusted-secret"));
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(Map.of("method", exchange.getRequestMethod(), "path", exchange.getRequestURI().toASCIIString(),
                    "auth", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")),
                    "body", new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
            int status = exchange.getRequestURI().getPath().endsWith("C201") ? 404 : 200;
            byte[] response = (status == 404 ? "{\"code\":\"NOT_FOUND\",\"message\":\"not found\"}" : "{\"items\":[],\"pageInfo\":{\"hasMore\":false}}").getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        tools = new CrmTools("http://127.0.0.1:" + server.getAddress().getPort());
    }
    @AfterEach void stop() { server.stop(0); }
    private String call(String name, String args, ToolContext scope) {
        return Arrays.stream(ToolCallbacks.from(tools)).filter(t -> t.getToolDefinition().name().equals(name))
                .findFirst().orElseThrow().call(args, scope);
    }
    @Test void schemaNeverExposesCredentialOrBaseUrl() {
        var callbacks = ToolCallbacks.from(tools);
        assertEquals(5, callbacks.length);
        for (var tool : callbacks) {
            assertFalse(tool.getToolDefinition().inputSchema().contains("fixtureToken"));
            assertFalse(tool.getToolDefinition().inputSchema().contains("baseUrl"));
        }
    }
    @Test void queryIsEncodedAndNullsOmittedWithTrustedIdentity() {
        call("listCustomers", "{\"limit\":2,\"cursor\":\"abc+/=\",\"expiresBefore\":\"2026-11-01\"}", context);
        assertEquals("Bearer trusted-secret", requests.getFirst().get("auth"));
        assertTrue(requests.getFirst().get("path").contains("cursor=abc%2B%2F%3D"));
        assertFalse(requests.getFirst().get("path").contains("inactiveBefore"));
        assertFalse(requests.getFirst().get("path").contains("null"));
    }
    @Test void writesSendExactlyBusinessBodyAndPath() throws Exception {
        call("createTask", "{\"request\":{\"customerId\":\"C102\",\"title\":\"样本\",\"dueDate\":\"2026-10-19\",\"idempotencyKey\":\"key\"}}", context);
        var mapper = new ObjectMapper();
        assertEquals("POST", requests.getFirst().get("method"));
        assertEquals("样本", mapper.readTree(requests.getFirst().get("body")).path("title").asText());
        assertFalse(requests.getFirst().get("body").contains("request"));
        call("updateTask", "{\"id\":\"T0001\",\"request\":{\"status\":\"done\",\"expectedVersion\":1}}", context);
        assertEquals("/tasks/T0001", requests.getLast().get("path"));
        assertEquals("PUT", requests.getLast().get("method"));
        assertEquals(1, mapper.readTree(requests.getLast().get("body")).path("expectedVersion").asInt());
    }
    @Test void missingTrustedIdentityDoesNotSendHttp() {
        assertThrows(RuntimeException.class, () -> call("getCustomer", "{\"id\":\"C101\"}", new ToolContext(Map.of())));
        assertTrue(requests.isEmpty());
    }
    @Test void deniedHttpIsExplicitAndAudited() throws Exception {
        var result = new ObjectMapper().readTree(call("getCustomer", "{\"id\":\"C201\"}", context));
        assertEquals(404, result.path("httpStatus").asInt());
        assertEquals("NOT_FOUND", result.path("error").path("code").asText());
        assertEquals(404, tools.events().getFirst().path("httpStatus").asInt());
        assertFalse(tools.events().toString().contains("trusted-secret"));
    }
    @Test void invalidJsonDoesNotReachHttp() {
        assertThrows(RuntimeException.class, () -> call("createTask", "{broken}", context));
        assertTrue(requests.isEmpty());
    }
}

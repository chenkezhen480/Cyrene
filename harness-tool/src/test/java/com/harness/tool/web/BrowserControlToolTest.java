package com.harness.tool.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserControlToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String workerUrl;
    private AtomicReference<String> authorization;
    private AtomicReference<String> requestBody;

    @BeforeEach
    void setUp() throws Exception {
        authorization = new AtomicReference<>();
        requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/browser/action", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    {"browserSessionId":"session-1","allowedOrigin":"https://example.com:443"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        workerUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void forwardsOnlyStructuredActionWithBearerToken() throws Exception {
        BrowserControlTool tool = new BrowserControlTool(
                new OkHttpClient(), workerUrl, "worker-secret");

        String result = tool.execute(MAPPER.createObjectNode()
                .put("action", "observe")
                .put("browserSessionId", "session-1")
                .put("unexpected", "must-not-forward"));

        assertThat(result).contains("session-1");
        assertThat(authorization.get()).isEqualTo("Bearer worker-secret");
        assertThat(requestBody.get())
                .contains("\"action\":\"observe\"")
                .contains("\"browserSessionId\":\"session-1\"")
                .doesNotContain("unexpected");
    }

    @Test
    void requiresConfirmationOnlyForInteractiveActions() {
        BrowserControlTool tool = new BrowserControlTool(
                new OkHttpClient(), workerUrl, "worker-secret");

        assertThat(tool.requiresConfirmation(
                MAPPER.createObjectNode().put("action", "observe"))).isFalse();
        assertThat(tool.requiresConfirmation(
                MAPPER.createObjectNode().put("action", "scroll"))).isFalse();
        assertThat(tool.requiresConfirmation(
                MAPPER.createObjectNode().put("action", "click"))).isTrue();
        assertThat(tool.requiresConfirmation(
                MAPPER.createObjectNode().put("action", "type"))).isTrue();
    }
}

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                    {"url":"https://example.com","content":"Rendered text","hasMore":false}
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
        AuthorizedUrlContext.clear();
        server.stop(0);
    }

    @Test
    void opensAuthorizedUrlWithBearerToken() throws Exception {
        BrowserControlTool tool = new BrowserControlTool(
                new OkHttpClient(), workerUrl, "worker-secret");

        AuthorizedUrlContext.setFromUserText("https://example.com");
        String result = tool.execute(MAPPER.createObjectNode()
                .put("url", "https://example.com")
                .put("maxChars", 100)
                .put("unexpected", "must-not-forward"));

        assertThat(result).contains("Rendered text");
        assertThat(authorization.get()).isEqualTo("Bearer worker-secret");
        assertThat(requestBody.get())
                .contains("\"action\":\"open\"")
                .contains("\"url\":\"https://example.com\"")
                .doesNotContain("unexpected");
    }

    @Test
    void rejectsControlActionsAndUnauthorizedUrls() {
        BrowserControlTool tool = new BrowserControlTool(
                new OkHttpClient(), workerUrl, "worker-secret");

        assertThatThrownBy(() -> tool.execute(MAPPER.createObjectNode()
                .put("url", "https://example.com").put("action", "click")))
                .hasMessageContaining("URL reading only");
        AuthorizedUrlContext.setFromUserText("https://example.com");
        assertThatThrownBy(() -> tool.execute(MAPPER.createObjectNode()
                .put("url", "https://other.example.com")))
                .hasMessageContaining("outside the scope");
    }
}

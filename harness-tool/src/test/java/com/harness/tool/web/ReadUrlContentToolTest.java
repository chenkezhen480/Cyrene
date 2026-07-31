package com.harness.tool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadUrlContentToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private AtomicReference<String> article;
    private ReadUrlContentTool tool;

    @BeforeEach
    void setUp() throws Exception {
        article = new AtomicReference<>("""
                <html><head><title>Example article</title></head><body>
                <nav>Navigation should not appear</nav>
                <article><h1>Heading</h1><p>First paragraph with useful content.</p>
                <p>Second paragraph for pagination.</p></article>
                </body></html>
                """);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/article", exchange -> {
            byte[] body = article.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/article");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        AuthorizedUrlContext.setFromUserText(
                "Read " + baseUrl + "/article and " + baseUrl + "/redirect");
        tool = new ReadUrlContentTool(
                new OkHttpClient.Builder()
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .build(),
                new UrlSafetyPolicy(true, List.of()),
                64 * 1024,
                24,
                100);
    }

    @AfterEach
    void tearDown() {
        AuthorizedUrlContext.clear();
        server.stop(0);
    }

    @Test
    void extractsMainContentAndPaginatesWithBoundCursor() throws Exception {
        JsonNode first = MAPPER.readTree(tool.execute(
                MAPPER.createObjectNode()
                        .put("url", baseUrl + "/article")
                        .put("maxChars", 24)));

        assertThat(first.path("title").asText()).isEqualTo("Example article");
        assertThat(first.path("content").asText()).contains("Heading");
        assertThat(first.path("content").asText()).doesNotContain("Navigation");
        assertThat(first.path("hasMore").asBoolean()).isTrue();

        JsonNode second = MAPPER.readTree(tool.execute(
                MAPPER.createObjectNode()
                        .put("url", baseUrl + "/article")
                        .put("cursor", first.path("nextCursor").asText())
                        .put("maxChars", 100)));

        assertThat(second.path("pageStart").asInt()).isEqualTo(first.path("pageEnd").asInt());
        assertThat(second.path("content").asText()).isNotBlank();
        assertThat(second.path("hasMore").asBoolean()).isFalse();
    }

    @Test
    void validatesEveryRedirectTarget() throws Exception {
        JsonNode result = MAPPER.readTree(tool.execute(
                MAPPER.createObjectNode().put("url", baseUrl + "/redirect")));

        assertThat(result.path("finalUrl").asText()).isEqualTo(baseUrl + "/article");
    }

    @Test
    void rejectsCursorAfterContentChanges() throws Exception {
        JsonNode first = MAPPER.readTree(tool.execute(
                MAPPER.createObjectNode()
                        .put("url", baseUrl + "/article")
                        .put("maxChars", 24)));
        article.set("<html><body><article>Changed content</article></body></html>");

        assertThatThrownBy(() -> tool.execute(
                MAPPER.createObjectNode()
                        .put("url", baseUrl + "/article")
                        .put("cursor", first.path("nextCursor").asText())))
                .hasMessageContaining("Invalid or stale pagination cursor");
    }
}

package com.harness.tool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.ResultStatus;
import com.harness.core.model.ToolExecutionOutcome;
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
    private AtomicReference<String> receivedUserAgent;
    private AtomicReference<String> receivedAccept;
    private AtomicReference<String> receivedAcceptLanguage;
    private ReadUrlContentTool tool;

    @BeforeEach
    void setUp() throws Exception {
        receivedUserAgent = new AtomicReference<>();
        receivedAccept = new AtomicReference<>();
        receivedAcceptLanguage = new AtomicReference<>();
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
        serveHtml("/empty-article", """
                <html><head><title>Paged article</title></head><body>
                <article></article>
                <div id="mw-content-text"><p>Body level content must survive.</p></div>
                </body></html>
                """);
        serveHtml("/blank-page", """
                <html><head><title>Blank</title></head><body>
                <script>var x = 1;</script>
                </body></html>
                """);
        server.createContext("/echo-ua", exchange -> {
            receivedUserAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            receivedAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
            receivedAcceptLanguage.set(
                    exchange.getRequestHeaders().getFirst("Accept-Language"));
            byte[] body = "<html><body><article>ok</article></body></html>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        AuthorizedUrlContext.setFromUserText(
                "Read " + baseUrl + "/article and " + baseUrl + "/redirect"
                        + " and " + baseUrl + "/empty-article and " + baseUrl + "/blank-page"
                        + " and " + baseUrl + "/echo-ua");
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

    private void serveHtml(String path, String html) {
        server.createContext(path, exchange -> {
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
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
    void fallsBackToPageBodyWhenPrimaryContainersAreEmpty() throws Exception {
        // 空的 <article/> 曾让整页被判成 EMPTY，而 EMPTY 在 AdaptiveReflector 里算一次失败。
        ToolExecutionOutcome outcome = tool.executeOutcome(
                MAPPER.createObjectNode()
                        .put("url", baseUrl + "/empty-article")
                        .put("maxChars", 100));

        assertThat(outcome.resultStatus()).isEqualTo(ResultStatus.AVAILABLE);
        assertThat(MAPPER.readTree(outcome.content().modelContent())
                .path("content").asText()).contains("Body level content must survive.");
    }

    @Test
    void reportsEmptyOnlyWhenTheWholePageHasNoText() throws Exception {
        ToolExecutionOutcome outcome = tool.executeOutcome(
                MAPPER.createObjectNode().put("url", baseUrl + "/blank-page"));

        assertThat(outcome.resultStatus()).isEqualTo(ResultStatus.EMPTY);
    }

    @Test
    void buildsDefaultUserAgentFromChromeVersion() {
        assertThat(ReadUrlContentTool.chromeUserAgent(160))
                .contains("Chrome/160.0.0.0")
                .contains("Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        assertThat(ReadUrlContentTool.chromeUserAgent(0))
                .contains("Chrome/153.0.0.0");
    }

    @Test
    void sendsBrowserConsistentHeaders() throws Exception {
        tool.execute(MAPPER.createObjectNode().put("url", baseUrl + "/echo-ua"));

        assertThat(receivedAccept.get())
                .startsWith("text/html,application/xhtml+xml")
                .contains("*/*;q=0.8");
        assertThat(receivedAcceptLanguage.get()).isEqualTo("zh-CN,zh;q=0.9,en;q=0.8");
        assertThat(receivedUserAgent.get()).contains("Chrome/153.0.0.0");
    }

    @Test
    void sendsTheConfiguredUserAgent() throws Exception {
        ReadUrlContentTool custom = new ReadUrlContentTool(
                new OkHttpClient.Builder()
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .build(),
                new UrlSafetyPolicy(true, List.of()),
                64 * 1024, 24, 100, "My-Agent/9.9");

        custom.execute(MAPPER.createObjectNode().put("url", baseUrl + "/echo-ua"));

        assertThat(receivedUserAgent.get()).isEqualTo("My-Agent/9.9");
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

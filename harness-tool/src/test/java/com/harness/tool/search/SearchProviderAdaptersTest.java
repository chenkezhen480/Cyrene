package com.harness.tool.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SearchProviderAdaptersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void brave_mapsRequestAndResponse() throws Exception {
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        HttpServer server = server("/brave", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-Subscription-Token"));
            query.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, """
                    {"web":{"results":[{"title":"Result","url":"https://example.com/a","description":"Snippet"}]}}
                    """);
        });
        try {
            BraveSearchProvider provider = new BraveSearchProvider(
                    new OkHttpClient(), MAPPER, endpoint(server, "/brave"), "secret");

            SearchResponse response = provider.search(new SearchRequest(
                    "Java", 5, "zh-CN",
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-01-31T00:00:00Z"), Set.of(), Set.of()));

            assertThat(response.results()).singleElement()
                    .extracting(SearchResult::title, SearchResult::snippet)
                    .containsExactly("Result", "Snippet");
            assertThat(token.get()).isEqualTo("secret");
            assertThat(query.get()).contains("search_lang=zh", "freshness=2026-01-01to2026-01-31");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void exa_mapsFiltersAndContent() throws Exception {
        AtomicReference<String> key = new AtomicReference<>();
        AtomicReference<JsonNode> payload = new AtomicReference<>();
        HttpServer server = server("/exa", exchange -> {
            key.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            payload.set(MAPPER.readTree(exchange.getRequestBody()));
            respond(exchange, """
                    {"results":[{"title":"Paper","url":"https://example.com/p","publishedDate":"2026-01-02T03:04:05Z","highlights":["Relevant text"]}]}
                    """);
        });
        try {
            ExaSearchProvider provider = new ExaSearchProvider(
                    new OkHttpClient(), MAPPER, endpoint(server, "/exa"), "secret");

            SearchResponse response = provider.search(new SearchRequest(
                    "agents", 4, "en", Instant.parse("2026-01-01T00:00:00Z"), null,
                    Set.of("openai.com"), Set.of("spam.example")));

            assertThat(response.results().get(0).snippet()).isEqualTo("Relevant text");
            assertThat(response.results().get(0).publishedAt()).isEqualTo("2026-01-02T03:04:05Z");
            assertThat(key.get()).isEqualTo("secret");
            assertThat(payload.get().path("includeDomains").get(0).asText()).isEqualTo("openai.com");
            assertThat(payload.get().path("excludeDomains").get(0).asText()).isEqualTo("spam.example");
            assertThat(payload.get().path("contents").path("highlights").asBoolean()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer server(String path, com.sun.net.httpserver.HttpHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(path, handler);
        server.start();
        return server;
    }

    private static String endpoint(HttpServer server, String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String json) throws java.io.IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}

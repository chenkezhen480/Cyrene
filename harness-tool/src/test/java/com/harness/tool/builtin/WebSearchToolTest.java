package com.harness.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ToolResult;
import com.sun.net.httpserver.HttpServer;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebSearchToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void formatResponse_returnsJsonEnvelopeAndDeduplicatesNormalizedUrls() throws Exception {
        WebSearchTool tool = tool(List.of("bing", "google"), 2);
        String response = """
                {
                  "results": [
                    {
                      "title":"First",
                      "url":"HTTPS://Example.com:443/docs#top",
                      "content":"first snippet",
                      "engines":["bing"],
                      "score":9.5,
                      "category":"general"
                    },
                    {
                      "title":"Duplicate",
                      "url":"https://example.com/docs#other",
                      "content":"lower-ranked duplicate",
                      "engine":"google",
                      "score":8.0
                    },
                    {
                      "title":"Second",
                      "url":"https://second.example/path",
                      "content":"second snippet",
                      "engine":"google"
                    },
                    {
                      "title":"Beyond limit",
                      "url":"https://third.example/"
                    }
                  ],
                  "unresponsive_engines":[["google","HTTP 429"]]
                }
                """;

        WebSearchTool.FormattedResponse formatted =
                tool.formatResponse(response, "Java 21");
        JsonNode envelope = MAPPER.valueToTree(formatted.envelope());

        assertThat(formatted.resultStatus()).isEqualTo(ToolResult.ResultStatus.SUCCESS);
        assertThat(envelope.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(envelope.path("data").path("query").asText()).isEqualTo("Java 21");
        JsonNode results = envelope.path("data").path("results");
        assertThat(results).hasSize(2);
        assertThat(results.get(0).path("rank").asInt()).isEqualTo(1);
        assertThat(results.get(0).path("url").asText())
                .isEqualTo("https://example.com/docs");
        assertThat(results.get(0).path("engine").asText()).isEqualTo("bing");
        assertThat(results.get(0).path("score").asDouble()).isEqualTo(9.5);
        assertThat(results.get(1).path("rank").asInt()).isEqualTo(3);
        assertThat(results.get(1).has("score")).isFalse();
        assertThat(results.get(1).has("category")).isFalse();
        assertThat(envelope.path("meta").path("unresponsiveEngines").get(0)
                .path("engine").asText()).isEqualTo("google");
        assertThat(envelope.path("meta").path("upstreamResultCount").asInt()).isEqualTo(4);
        assertThat(envelope.path("meta").path("returnedResultCount").asInt()).isEqualTo(2);
    }

    @Test
    void formatResponse_returnsEmptyWhenHealthyEnginesHaveNoResults() throws Exception {
        WebSearchTool.FormattedResponse formatted = tool(List.of("bing", "google"), 8)
                .formatResponse("""
                        {
                          "results":[],
                          "unresponsive_engines":[["google","timeout"]]
                        }
                        """, "no-match-query");

        assertThat(formatted.resultStatus()).isEqualTo(ToolResult.ResultStatus.EMPTY);
        assertThat(formatted.envelope().status().name()).isEqualTo("EMPTY");
        assertThat(formatted.envelope().data().results()).isEmpty();
    }

    @Test
    void formatResponse_throwsWhenEveryConfiguredEngineFailed() {
        WebSearchTool tool = tool(List.of("bing", "google"), 8);

        assertThatThrownBy(() -> tool.formatResponse("""
                        {
                          "results":[],
                          "unresponsive_engines":[
                            ["bing","timeout"],
                            ["google","CAPTCHA"]
                          ]
                        }
                        """, "failed-query"))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("All configured SearXNG engines failed")
                .hasMessageContaining("CAPTCHA");
    }

    @Test
    void buildRequestUrl_sendsSupportedSearchParameters() {
        HttpUrl url = tool(List.of("bing", "duckduckgo"), 8)
                .buildRequestUrl("Java 21 最新特性", "zh-CN");

        assertThat(url.queryParameter("q")).isEqualTo("Java 21 最新特性");
        assertThat(url.queryParameter("format")).isEqualTo("json");
        assertThat(url.queryParameter("categories")).isEqualTo("general");
        assertThat(url.queryParameter("language")).isEqualTo("zh-CN");
        assertThat(url.queryParameter("engines")).isEqualTo("bing,duckduckgo");
    }

    @Test
    void execute_rejectsInvalidLanguageBeforeSendingRequest() throws Exception {
        WebSearchTool tool = tool(List.of("bing"), 8);

        assertThatThrownBy(() -> tool.execute(MAPPER.readTree("""
                        {"query":"Java 21","language":"../../invalid"}
                        """)))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("Invalid language parameter");
    }

    @Test
    void execute_returnsStructuredJsonFromHttpResponse() throws Exception {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> {
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] body = """
                    {
                      "results":[{
                        "title":"Java 21",
                        "url":"https://example.com/java-21#features",
                        "content":"Release features",
                        "engine":"bing",
                        "score":2.0,
                        "category":"general"
                      }],
                      "unresponsive_engines":[]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            WebSearchTool tool = new WebSearchTool(
                    new OkHttpClient(),
                    MAPPER,
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    List.of("bing"),
                    8);

            JsonNode envelope = MAPPER.readTree(tool.execute(MAPPER.readTree("""
                    {"query":"Java 21 最新特性","language":"zh-CN"}
                    """)));

            assertThat(envelope.path("status").asText()).isEqualTo("SUCCESS");
            assertThat(envelope.path("data").path("results").get(0)
                    .path("url").asText()).isEqualTo("https://example.com/java-21");
            assertThat(rawQuery.get()).contains("language=zh-CN");
            assertThat(rawQuery.get()).contains("engines=bing");
            assertThat(ToolResult.consumeCurrentStatus())
                    .isEqualTo(ToolResult.ResultStatus.SUCCESS);
        } finally {
            ToolResult.clearCurrentStatus();
            server.stop(0);
        }
    }

    @Test
    void constructor_rejectsInvalidResultLimit() {
        assertThatThrownBy(() -> tool(List.of("bing"), 21))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RESULT_LIMIT must be between 1 and 20");
    }

    private static WebSearchTool tool(List<String> engines, int resultLimit) {
        return new WebSearchTool(
                new OkHttpClient(),
                MAPPER,
                "http://127.0.0.1:8888",
                engines,
                resultLimit);
    }
}

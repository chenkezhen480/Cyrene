package com.harness.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ResultStatus;
import com.harness.tool.web.AuthorizedUrlContext;
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

        assertThat(formatted.resultStatus()).isEqualTo(ResultStatus.AVAILABLE);
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
    void formatResponse_authorizesReturnedResultUrlsForReading() throws Exception {
        AuthorizedUrlContext.setFromUserText("帮我查一下 Tsaritsa 的背景");
        try {
            tool(List.of("bing"), 8).formatResponse("""
                    {
                      "results": [
                        {
                          "title":"Tsaritsa",
                          "url":"https://villains.fandom.com/wiki/Tsaritsa",
                          "engine":"bing"
                        }
                      ]
                    }
                    """, "Tsaritsa");

            AuthorizedUrlContext.requireAuthorized(
                    "https://villains.fandom.com/wiki/Tsaritsa", "read_url_content");
        } finally {
            AuthorizedUrlContext.clear();
        }
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

        assertThat(formatted.resultStatus()).isEqualTo(ResultStatus.EMPTY);
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
    void buildRequestUrl_omitsLanguageWhenUnresolved() {
        HttpUrl url = tool(List.of("bing"), 8).buildRequestUrl("query", null);

        assertThat(url.queryParameter("language")).isNull();
    }

    @Test
    void resolveLanguage_prefersModelArgAndFallsBackToConfiguredDefault() {
        WebSearchTool configured = new WebSearchTool(
                new OkHttpClient(), MAPPER, "http://127.0.0.1:8888",
                List.of("bing"), 8, "zh-CN", List.of(), 1, 0.0);

        assertThat(configured.resolveLanguage(null)).isEqualTo("zh-CN");
        assertThat(configured.resolveLanguage("en-US")).isEqualTo("en-US");
        assertThat(configured.resolveLanguage("all")).isNull();
        assertThat(configured.resolveLanguage("ALL")).isNull();
        assertThat(configured.resolveLanguage("auto")).isNull();

        WebSearchTool upperDefault = new WebSearchTool(
                new OkHttpClient(), MAPPER, "http://127.0.0.1:8888",
                List.of("bing"), 8, "ALL", List.of(), 1, 0.0);
        assertThat(upperDefault.resolveLanguage(null)).isNull();

        WebSearchTool unconfigured = tool(List.of("bing"), 8);
        assertThat(unconfigured.resolveLanguage(null)).isNull();
        assertThat(unconfigured.resolveLanguage("all")).isNull();
    }

    @Test
    void constructor_rejectsInvalidQualityConfig() {
        assertThatThrownBy(() -> new WebSearchTool(new OkHttpClient(), MAPPER,
                "http://127.0.0.1:8888", List.of("bing"), 8,
                "not a language!", List.of(), 1, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HARNESS_TOOL_WEB_SEARCH_LANGUAGE");
        assertThatThrownBy(() -> new WebSearchTool(new OkHttpClient(), MAPPER,
                "http://127.0.0.1:8888", List.of("bing"), 8,
                null, List.of(), 0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HARNESS_TOOL_WEB_SEARCH_MIN_ENGINES");
        assertThatThrownBy(() -> new WebSearchTool(new OkHttpClient(), MAPPER,
                "http://127.0.0.1:8888", List.of("bing"), 8,
                null, List.of(), 1, -0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HARNESS_TOOL_WEB_SEARCH_MIN_SCORE");
    }

    @Test
    void formatResponse_appliesConfiguredQualityFilters() throws Exception {
        WebSearchTool tool = new WebSearchTool(
                new OkHttpClient(), MAPPER, "http://127.0.0.1:8888",
                List.of("bing", "google"), 8,
                null,
                List.of("blocked.example"),
                2,
                1.0);
        String response = """
                {
                  "results": [
                    {
                      "title":"Blocked domain",
                      "url":"https://sub.blocked.example/x",
                      "engine":"bing",
                      "engines":["bing"],
                      "score":9.0
                    },
                    {
                      "title":"Single engine endorsement",
                      "url":"https://a.example/",
                      "engines":["bing"],
                      "score":9.0
                    },
                    {
                      "title":"Below score threshold",
                      "url":"https://b.example/",
                      "engines":["bing","google"],
                      "score":0.5
                    },
                    {
                      "title":"Kept",
                      "url":"https://c.example/",
                      "engines":["bing","google"],
                      "score":3.0
                    }
                  ],
                  "unresponsive_engines":[]
                }
                """;

        WebSearchTool.FormattedResponse formatted = tool.formatResponse(response, "q");
        JsonNode envelope = MAPPER.valueToTree(formatted.envelope());
        JsonNode results = envelope.path("data").path("results");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).path("title").asText()).isEqualTo("Kept");
        assertThat(results.get(0).path("rank").asInt()).isEqualTo(4);
    }

    @Test
    void formatResponse_flagsLowConfidenceWhenEnginesFail() throws Exception {
        WebSearchTool tool = tool(List.of("bing", "google", "brave"), 8);

        WebSearchTool.FormattedResponse formatted = tool.formatResponse("""
                {
                  "results":[
                    {"title":"A","url":"https://a.example/","engine":"bing","score":2.0},
                    {"title":"B","url":"https://b.example/","engine":"bing","score":1.0}
                  ],
                  "unresponsive_engines":[["google","timeout"],["brave","CAPTCHA"]]
                }
                """, "q");
        JsonNode envelope = MAPPER.valueToTree(formatted.envelope());

        assertThat(envelope.path("meta").path("lowConfidence").asBoolean()).isTrue();
        assertThat(envelope.path("meta").path("qualityNotice").asText())
                .contains("2 engines unresponsive")
                .contains("google: timeout")
                .contains("brave: CAPTCHA");
        assertThat(envelope.path("meta").path("contributingEngines").get(0).asText())
                .isEqualTo("bing");
    }

    @Test
    void formatResponse_multiEngineOverlapIsNotFlaggedLowConfidence() throws Exception {
        WebSearchTool tool = tool(List.of("bing", "google", "brave"), 8);

        // 健康场景：合并结果 engine 字段只写首个引擎，但 engines 数组是多引擎并集 —— 不得误报
        WebSearchTool.FormattedResponse formatted = tool.formatResponse("""
                {
                  "results":[
                    {"title":"A","url":"https://a.example/","engine":"bing","engines":["bing","google"],"score":2.0},
                    {"title":"B","url":"https://b.example/","engine":"bing","engines":["bing","google"],"score":1.5},
                    {"title":"C","url":"https://c.example/","engine":"bing","engines":["bing","google"],"score":1.0}
                  ],
                  "unresponsive_engines":[]
                }
                """, "q");
        JsonNode envelope = MAPPER.valueToTree(formatted.envelope());

        assertThat(envelope.path("meta").path("lowConfidence").asBoolean()).isFalse();
        assertThat(envelope.path("meta").has("qualityNotice")).isFalse();
        assertThat(envelope.path("meta").path("contributingEngines").get(0).asText())
                .isEqualTo("bing");
        assertThat(envelope.path("meta").path("contributingEngines").get(1).asText())
                .isEqualTo("google");
    }

    @Test
    void formatResponse_flagsLowConfidenceWhenAllResultsFromOneEngine() throws Exception {
        WebSearchTool tool = tool(List.of("bing", "google", "brave"), 8);

        WebSearchTool.FormattedResponse flagged = tool.formatResponse("""
                {
                  "results":[
                    {"title":"A","url":"https://a.example/","engine":"bing","score":2.0},
                    {"title":"B","url":"https://b.example/","engine":"bing","score":1.5},
                    {"title":"C","url":"https://c.example/","engine":"bing","score":1.0}
                  ],
                  "unresponsive_engines":[]
                }
                """, "q");
        JsonNode flaggedEnvelope = MAPPER.valueToTree(flagged.envelope());
        assertThat(flaggedEnvelope.path("meta").path("lowConfidence").asBoolean()).isTrue();
        assertThat(flaggedEnvelope.path("meta").path("qualityNotice").asText())
                .contains("all 3 results came from 1 engine (bing)");

        WebSearchTool.FormattedResponse sparse = tool.formatResponse("""
                {
                  "results":[
                    {"title":"A","url":"https://a.example/","engine":"bing","score":2.0}
                  ],
                  "unresponsive_engines":[]
                }
                """, "q");
        JsonNode sparseEnvelope = MAPPER.valueToTree(sparse.envelope());
        assertThat(sparseEnvelope.path("meta").path("lowConfidence").asBoolean()).isFalse();
        assertThat(sparseEnvelope.path("meta").has("qualityNotice")).isFalse();
    }

    @Test
    void execute_usesConfiguredDefaultLanguageAndAcceptLanguageHeader() throws Exception {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        AtomicReference<String> acceptLanguage = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> {
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            acceptLanguage.set(exchange.getRequestHeaders().getFirst("Accept-Language"));
            byte[] body = """
                    {
                      "results":[{
                        "title":"T",
                        "url":"https://e.example/",
                        "engine":"bing"
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
            WebSearchTool configured = new WebSearchTool(
                    new OkHttpClient(), MAPPER,
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    List.of("bing"), 8, "zh-CN", List.of(), 1, 0.0);

            configured.executeOutcome(MAPPER.readTree("{\"query\":\"q\"}"));
            assertThat(rawQuery.get()).contains("language=zh-CN");
            assertThat(acceptLanguage.get()).isEqualTo("zh-CN");

            configured.executeOutcome(MAPPER.readTree("{\"query\":\"q\",\"language\":\"all\"}"));
            assertThat(rawQuery.get()).doesNotContain("language=");
            assertThat(acceptLanguage.get()).isNull();
        } finally {
            server.stop(0);
        }
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

            var outcome = tool.executeOutcome(MAPPER.readTree("""
                    {"query":"Java 21 最新特性","language":"zh-CN"}
                    """));
            JsonNode envelope = MAPPER.readTree(outcome.content().modelContent());

            assertThat(envelope.path("status").asText()).isEqualTo("SUCCESS");
            assertThat(envelope.path("data").path("results").get(0)
                    .path("url").asText()).isEqualTo("https://example.com/java-21");
            assertThat(rawQuery.get()).contains("language=zh-CN");
            assertThat(rawQuery.get()).contains("engines=bing");
            assertThat(outcome.resultStatus()).isEqualTo(ResultStatus.AVAILABLE);
        } finally {
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

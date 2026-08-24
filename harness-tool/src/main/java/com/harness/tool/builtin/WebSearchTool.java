package com.harness.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ToolResult;
import com.harness.core.model.ToolSpec;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.tool.Tool;
import com.harness.tool.protocol.ToolEnvelope;
import com.harness.tool.protocol.ToolEnvelopeStatus;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Built-in web search tool backed by a self-hosted SearXNG instance.
 */
public class WebSearchTool implements Tool {

    public static final String TOOL_NAME = "web_search";
    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    private static final String DEFAULT_ENGINES =
            "bing,duckduckgo,brave,google,wikipedia";
    private static final int MAX_RESULT_LIMIT = 20;
    private static final Pattern LANGUAGE_PATTERN = Pattern.compile(
            "^(?:all|auto|[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*)$");

    private final ObjectMapper mapper;
    private final OkHttpClient http;
    private final String baseUrl;
    private final List<String> engines;
    private final int resultLimit;

    public WebSearchTool() {
        this(new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build(),
                new ObjectMapper(),
                EnvConfig.get().getString(
                        EnvKey.TOOL_WEB_SEARCH_SEARXNG_URL,
                        "http://localhost:8888"),
                configuredEngines(EnvConfig.get()),
                EnvConfig.get().getInt(EnvKey.TOOL_WEB_SEARCH_RESULT_LIMIT, 8));
    }

    WebSearchTool(
            OkHttpClient http,
            ObjectMapper mapper,
            String baseUrl,
            List<String> engines,
            int resultLimit
    ) {
        this.http = Objects.requireNonNull(http, "http");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl").replaceAll("/+$", "");
        this.engines = normalizeEngines(engines);
        if (resultLimit < 1 || resultLimit > MAX_RESULT_LIMIT) {
            throw new IllegalArgumentException(
                    "HARNESS_TOOL_WEB_SEARCH_RESULT_LIMIT must be between 1 and "
                            + MAX_RESULT_LIMIT);
        }
        this.resultLimit = resultLimit;
        log.info("WebSearch initialized, SearXNG endpoint: {}", baseUrl);
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                TOOL_NAME,
                "Search the web for real-time information. Use this tool when: the user asks about current events, news, recent developments, today's weather/stock/price, or any question that requires up-to-date information not in your training data. Also use when the user explicitly asks to search or look up something online. Results use a structured JSON envelope; partial upstream failures are reported in meta.unresponsiveEngines.",
                mapper.createObjectNode()
                        .put("type", "object")
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("properties",
                                mapper.createObjectNode()
                                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("query",
                                                mapper.createObjectNode()
                                                        .put("type", "string")
                                                        .put("description", "Search query"))
                                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("language",
                                                mapper.createObjectNode()
                                                        .put("type", "string")
                                                        .put("pattern", LANGUAGE_PATTERN.pattern())
                                                        .put("description",
                                                                "Optional search language, such as zh-CN or en-US. Specify it when language matters because auto detection can be influenced by the search server's region.")))
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("required",
                                mapper.createArrayNode().add("query"))
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        String query = arguments.has("query") ? arguments.get("query").asText() : null;
        if (query == null || query.isBlank()) {
            throw new ToolExecutionException(TOOL_NAME, "Missing required parameter: query");
        }
        String language = optionalLanguage(arguments);

        HttpUrl url = buildRequestUrl(query, language);

        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .get()
                .build();

        log.debug("Web search: query={}", query);

        try (Response response = http.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("SearXNG HTTP " + response.code() + ": " + body);
            }
            FormattedResponse formatted = formatResponse(body, query);
            ToolResult.setCurrentStatus(formatted.resultStatus());
            return mapper.writeValueAsString(formatted.envelope());
        } catch (ToolExecutionException exception) {
            throw exception;
        } catch (IOException e) {
            throw new ToolExecutionException(
                    TOOL_NAME,
                    "SearXNG request failed (" + baseUrl + "): " + e.getMessage(),
                    e);
        }
    }

    HttpUrl buildRequestUrl(String query, String language) {
        HttpUrl endpoint = HttpUrl.parse(baseUrl + "/search");
        if (endpoint == null) {
            throw new ToolExecutionException(
                    TOOL_NAME, "Invalid SearXNG URL: " + baseUrl);
        }
        HttpUrl.Builder requestUrl = endpoint.newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("format", "json")
                .addQueryParameter("categories", "general")
                .addQueryParameter("engines", String.join(",", engines));
        if (language != null) {
            requestUrl.addQueryParameter("language", language);
        }
        return requestUrl.build();
    }

    FormattedResponse formatResponse(String json, String query) throws IOException {
        JsonNode root = mapper.readTree(json);
        SearxngResponseDiagnostics.Snapshot diagnostics =
                SearxngResponseDiagnostics.parse(root);
        log.debug(
                "Web search baseline: query={}, results={}, unresponsiveEngines={}",
                query,
                diagnostics.resultCount(),
                diagnostics.unresponsiveEngines().size());
        if (!diagnostics.unresponsiveEngines().isEmpty()) {
            log.warn(
                    "SearXNG partial engine failures for query {}: {}",
                    query,
                    diagnostics.unresponsiveEngines());
        }
        if (SearxngResponseDiagnostics.allConfiguredEnginesFailed(
                diagnostics, engines)) {
            throw new ToolExecutionException(
                    TOOL_NAME,
                    "All configured SearXNG engines failed: "
                            + diagnostics.unresponsiveEngines());
        }

        Map<String, WebSearchData.Result> uniqueResults = new LinkedHashMap<>();
        JsonNode results = root.path("results");
        if (results.isArray()) {
            int upstreamRank = 0;
            for (JsonNode result : results) {
                upstreamRank++;
                String normalizedUrl = normalizeUrl(result.path("url").asText(""));
                if (normalizedUrl == null || uniqueResults.containsKey(normalizedUrl)) {
                    continue;
                }
                uniqueResults.put(normalizedUrl, toResult(result, normalizedUrl, upstreamRank));
                if (uniqueResults.size() >= resultLimit) {
                    break;
                }
            }
        }

        List<WebSearchData.Result> returnedResults = List.copyOf(uniqueResults.values());
        ToolEnvelopeStatus envelopeStatus = returnedResults.isEmpty()
                ? ToolEnvelopeStatus.EMPTY
                : ToolEnvelopeStatus.SUCCESS;
        ToolResult.ResultStatus resultStatus = returnedResults.isEmpty()
                ? ToolResult.ResultStatus.EMPTY
                : ToolResult.ResultStatus.SUCCESS;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("queriedEngines", engines);
        meta.put("unresponsiveEngines", diagnostics.unresponsiveEngines());
        meta.put("upstreamResultCount", diagnostics.resultCount());
        meta.put("returnedResultCount", returnedResults.size());

        ToolEnvelope<WebSearchData> envelope = new ToolEnvelope<>(
                envelopeStatus,
                new WebSearchData(query, returnedResults),
                null,
                meta);
        return new FormattedResponse(envelope, resultStatus);
    }

    private static WebSearchData.Result toResult(
            JsonNode result,
            String normalizedUrl,
            int rank
    ) {
        JsonNode score = result.get("score");
        return new WebSearchData.Result(
                rank,
                result.path("title").asText("").trim(),
                normalizedUrl,
                result.path("content").asText("").trim(),
                resultEngine(result),
                score != null && score.isNumber() ? score.doubleValue() : null,
                nullableText(result, "category"));
    }

    private static String resultEngine(JsonNode result) {
        String engine = nullableText(result, "engine");
        if (engine != null) {
            return engine;
        }
        JsonNode resultEngines = result.path("engines");
        return resultEngines.isArray() && !resultEngines.isEmpty()
                ? resultEngines.get(0).asText(null)
                : null;
    }

    private static String nullableText(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().trim();
    }

    private static String optionalLanguage(JsonNode arguments) {
        String language = nullableText(arguments, "language");
        if (language == null) {
            return null;
        }
        if (!LANGUAGE_PATTERN.matcher(language).matches()) {
            throw new ToolExecutionException(
                    TOOL_NAME,
                    "Invalid language parameter: " + language);
        }
        return language;
    }

    private static String normalizeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        HttpUrl parsed = HttpUrl.parse(rawUrl.trim());
        return parsed == null ? null : parsed.newBuilder().fragment(null).build().toString();
    }

    private static List<String> normalizeEngines(List<String> configuredEngines) {
        Objects.requireNonNull(configuredEngines, "engines");
        List<String> normalized = new ArrayList<>();
        for (String engine : configuredEngines) {
            if (engine == null || engine.isBlank()) {
                continue;
            }
            String name = engine.trim().toLowerCase(Locale.ROOT);
            if (!normalized.contains(name)) {
                normalized.add(name);
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("At least one SearXNG engine must be configured");
        }
        return List.copyOf(normalized);
    }

    private static List<String> configuredEngines(EnvConfig config) {
        List<String> configured = config.getCommaList(EnvKey.TOOL_WEB_SEARCH_ENGINES);
        return configured.isEmpty() ? List.of(DEFAULT_ENGINES.split(",")) : configured;
    }

    record FormattedResponse(
            ToolEnvelope<WebSearchData> envelope,
            ToolResult.ResultStatus resultStatus
    ) {
    }
}

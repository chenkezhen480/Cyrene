package com.harness.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ResultStatus;
import com.harness.core.model.ToolExecutionOutcome;
import com.harness.core.model.ToolOutput;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
    /** 未指定时的默认搜索语言；null = 不限语言（中英混合） */
    private final String defaultLanguage;
    /** 已小写规范化的域名黑名单后缀 */
    private final List<String> blockedDomains;
    private final int minEngines;
    private final double minScore;

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
                EnvConfig.get().getInt(EnvKey.TOOL_WEB_SEARCH_RESULT_LIMIT, 8),
                configuredLanguage(EnvConfig.get()),
                EnvConfig.get().getCommaList(EnvKey.TOOL_WEB_SEARCH_BLOCKED_DOMAINS),
                EnvConfig.get().getInt(EnvKey.TOOL_WEB_SEARCH_MIN_ENGINES, 1),
                EnvConfig.get().getDouble(EnvKey.TOOL_WEB_SEARCH_MIN_SCORE, 0.0));
    }

    WebSearchTool(
            OkHttpClient http,
            ObjectMapper mapper,
            String baseUrl,
            List<String> engines,
            int resultLimit
    ) {
        this(http, mapper, baseUrl, engines, resultLimit, null, List.of(), 1, 0.0);
    }

    WebSearchTool(
            OkHttpClient http,
            ObjectMapper mapper,
            String baseUrl,
            List<String> engines,
            int resultLimit,
            String defaultLanguage,
            List<String> blockedDomains,
            int minEngines,
            double minScore
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
        if (defaultLanguage != null
                && !LANGUAGE_PATTERN.matcher(defaultLanguage).matches()) {
            throw new IllegalArgumentException(
                    "Invalid HARNESS_TOOL_WEB_SEARCH_LANGUAGE: " + defaultLanguage);
        }
        this.defaultLanguage = defaultLanguage == null
                || defaultLanguage.isBlank()
                || "all".equalsIgnoreCase(defaultLanguage)
                || "auto".equalsIgnoreCase(defaultLanguage) ? null : defaultLanguage;
        List<String> normalizedDomains = new ArrayList<>();
        for (String domain : Objects.requireNonNull(blockedDomains, "blockedDomains")) {
            if (domain == null || domain.isBlank()) {
                continue;
            }
            String name = domain.trim().toLowerCase(Locale.ROOT);
            if (!normalizedDomains.contains(name)) {
                normalizedDomains.add(name);
            }
        }
        this.blockedDomains = List.copyOf(normalizedDomains);
        if (minEngines < 1) {
            throw new IllegalArgumentException(
                    "HARNESS_TOOL_WEB_SEARCH_MIN_ENGINES must be >= 1");
        }
        this.minEngines = minEngines;
        if (Double.isNaN(minScore) || minScore < 0) {
            throw new IllegalArgumentException(
                    "HARNESS_TOOL_WEB_SEARCH_MIN_SCORE must be >= 0");
        }
        this.minScore = minScore;
        log.info("WebSearch initialized, SearXNG endpoint: {}", baseUrl);
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                TOOL_NAME,
                "Search the web for real-time information. Use this tool when: the user asks about current events, news, recent developments, today's weather/stock/price, or any question that requires up-to-date information not in your training data. Also use when the user explicitly asks to search or look up something online. Results use a structured JSON envelope; partial upstream failures are reported in meta.unresponsiveEngines, and meta.lowConfidence with meta.qualityNotice flags degraded coverage (several engines failed, or all results came from one engine) — treat such results as less reliable or search again with different wording.",
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
                                                                "Optional search language, such as zh-CN or en-US. Specify it when language matters because auto detection can be influenced by the search server's region. Use 'all' to explicitly allow mixed multilingual results.")))
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("required",
                                mapper.createArrayNode().add("query")),
                com.harness.core.model.ToolCapability.RETRIEVAL
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        return executeOutcome(arguments).content().modelContent();
    }

    @Override
    public ToolExecutionOutcome executeOutcome(JsonNode arguments) {
        String query = arguments.has("query") ? arguments.get("query").asText() : null;
        if (query == null || query.isBlank()) {
            throw new ToolExecutionException(TOOL_NAME, "Missing required parameter: query");
        }
        String language = optionalLanguage(arguments);
        String effectiveLanguage = resolveLanguage(language);

        HttpUrl url = buildRequestUrl(query, effectiveLanguage);

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .header("Accept", "application/json");
        if (effectiveLanguage != null) {
            // SearXNG 的部分引擎（send_accept_language_header）依赖该头决定结果语言
            requestBuilder.header("Accept-Language", effectiveLanguage);
        }
        Request request = requestBuilder.get().build();

        log.debug("Web search: query={}", query);

        try (Response response = http.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("SearXNG HTTP " + response.code() + ": " + body);
            }
            FormattedResponse formatted = formatResponse(body, query);
            String output = mapper.writeValueAsString(formatted.envelope());
            return ToolExecutionOutcome.succeeded(
                    ToolOutput.text(output), formatted.resultStatus());
        } catch (ToolExecutionException exception) {
            throw exception;
        } catch (IOException e) {
            throw new ToolExecutionException(
                    TOOL_NAME,
                    "SearXNG request failed (" + baseUrl + "): " + e.getMessage(),
                    e);
        }
    }

    /** @param language 已解析的生效语言（null = 不传 language 参数，由 SearXNG 默认语言决定） */
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

    /**
     * 解析生效语言：模型参数优先，其次环境配置默认值；all/auto 表示不限制语言。
     */
    String resolveLanguage(String requestedLanguage) {
        String value = requestedLanguage != null ? requestedLanguage : defaultLanguage;
        if (value == null || value.isBlank()
                || "all".equalsIgnoreCase(value) || "auto".equalsIgnoreCase(value)) {
            return null;
        }
        return value;
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
        Set<String> contributingEngines = new LinkedHashSet<>();
        JsonNode results = root.path("results");
        if (results.isArray()) {
            int upstreamRank = 0;
            for (JsonNode result : results) {
                upstreamRank++;
                String normalizedUrl = normalizeUrl(result.path("url").asText(""));
                if (normalizedUrl == null || uniqueResults.containsKey(normalizedUrl)) {
                    continue;
                }
                if (isBlockedDomain(normalizedUrl)
                        || engineEndorsement(result) < minEngines
                        || result.path("score").asDouble(0.0) < minScore) {
                    continue;
                }
                uniqueResults.put(normalizedUrl, toResult(result, normalizedUrl, upstreamRank));
                collectContributingEngines(result, contributingEngines);
                if (uniqueResults.size() >= resultLimit) {
                    break;
                }
            }
        }

        List<WebSearchData.Result> returnedResults = List.copyOf(uniqueResults.values());

        Set<String> failedEngines = failedQueriedEngines(diagnostics);
        // 低可信：多数引擎失败（结果可能只来自单一引擎），或全部结果都出自一个引擎（典型如
        // bing 在服务器 IP 上返回无关结果，此时引擎全部"在线"，unresponsive_engines 无信号）。
        // 引擎集合取自合并结果的 engines 数组并集，而非单数的 engine 字段（后者只是首个引擎），
        // 否则多引擎交叉命中的健康结果会被误判为单引擎。
        boolean lowConfidence = failedEngines.size() >= 2
                || (returnedResults.size() >= 3
                        && contributingEngines.size() == 1
                        && engines.size() >= 2);

        ToolEnvelopeStatus envelopeStatus = returnedResults.isEmpty()
                ? ToolEnvelopeStatus.EMPTY
                : ToolEnvelopeStatus.SUCCESS;
        ResultStatus resultStatus = returnedResults.isEmpty()
                ? ResultStatus.EMPTY
                : ResultStatus.AVAILABLE;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("queriedEngines", engines);
        meta.put("unresponsiveEngines", diagnostics.unresponsiveEngines());
        meta.put("contributingEngines", List.copyOf(contributingEngines));
        meta.put("upstreamResultCount", diagnostics.resultCount());
        meta.put("returnedResultCount", returnedResults.size());
        meta.put("lowConfidence", lowConfidence);
        if (lowConfidence) {
            meta.put("qualityNotice", qualityNotice(
                    diagnostics, failedEngines, contributingEngines, returnedResults));
        }

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

    /** 该结果 URL 的域名是否命中黑名单（按主域名后缀匹配，blog.csdn.net 命中 csdn.net）。 */
    private boolean isBlockedDomain(String normalizedUrl) {
        if (blockedDomains.isEmpty()) {
            return false;
        }
        HttpUrl parsed = HttpUrl.parse(normalizedUrl);
        if (parsed == null) {
            return false;
        }
        String host = parsed.host();
        for (String domain : blockedDomains) {
            if (host.equals(domain) || host.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    /** 结果被几个引擎同时命中（多引擎背书数）；缺 engines 字段的旧格式按 1 计。 */
    private static int engineEndorsement(JsonNode result) {
        JsonNode enginesNode = result.path("engines");
        return enginesNode.isArray() && !enginesNode.isEmpty() ? enginesNode.size() : 1;
    }

    /** 并集收集该结果的贡献引擎：优先 engines 数组（合并结果的完整引擎集），回退 engine 字段。 */
    private static void collectContributingEngines(JsonNode result, Set<String> into) {
        JsonNode enginesNode = result.path("engines");
        if (enginesNode.isArray() && !enginesNode.isEmpty()) {
            for (JsonNode engine : enginesNode) {
                String name = engine.asText(null);
                if (name != null && !name.isBlank()) {
                    into.add(name.trim().toLowerCase(Locale.ROOT));
                }
            }
            return;
        }
        String primary = resultEngine(result);
        if (primary != null) {
            into.add(primary.trim().toLowerCase(Locale.ROOT));
        }
    }

    private Set<String> failedQueriedEngines(SearxngResponseDiagnostics.Snapshot diagnostics) {
        Set<String> failed = new LinkedHashSet<>();
        for (SearxngResponseDiagnostics.EngineFailure failure : diagnostics.unresponsiveEngines()) {
            String name = failure.engine().trim().toLowerCase(Locale.ROOT);
            if (engines.contains(name)) {
                failed.add(name);
            }
        }
        return failed;
    }

    private static String qualityNotice(
            SearxngResponseDiagnostics.Snapshot diagnostics,
            Set<String> failedEngines,
            Set<String> contributingEngines,
            List<WebSearchData.Result> returnedResults
    ) {
        List<String> parts = new ArrayList<>();
        if (failedEngines.size() >= 2) {
            String detail = diagnostics.unresponsiveEngines().stream()
                    .filter(failure -> failedEngines.contains(
                            failure.engine().trim().toLowerCase(Locale.ROOT)))
                    .map(failure -> failure.engine() + ": " + failure.error())
                    .collect(Collectors.joining(", "));
            parts.add(failedEngines.size() + " engines unresponsive: " + detail);
        }
        if (returnedResults.size() >= 3 && contributingEngines.size() == 1) {
            parts.add("all " + returnedResults.size() + " results came from 1 engine ("
                    + contributingEngines.iterator().next() + ")");
        }
        return String.join("; ", parts);
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

    private static String configuredLanguage(EnvConfig config) {
        String value = config.getString(EnvKey.TOOL_WEB_SEARCH_LANGUAGE, "");
        return value.isBlank() ? null : value.trim();
    }

    record FormattedResponse(
            ToolEnvelope<WebSearchData> envelope,
            ResultStatus resultStatus
    ) {
    }
}

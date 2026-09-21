package com.harness.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ResultStatus;
import com.harness.core.model.ToolCapability;
import com.harness.core.model.ToolExecutionOutcome;
import com.harness.core.model.ToolOutput;
import com.harness.core.model.ToolSpec;
import com.harness.tool.Tool;
import com.harness.tool.protocol.ToolEnvelope;
import com.harness.tool.protocol.ToolEnvelopeStatus;
import com.harness.tool.search.BraveSearchProvider;
import com.harness.tool.search.ExaSearchProvider;
import com.harness.tool.search.SearchProvider;
import com.harness.tool.search.SearchProviderException;
import com.harness.tool.search.SearchRequest;
import com.harness.tool.search.SearchResponse;
import com.harness.tool.search.SearchResult;
import com.harness.tool.search.SearchRouter;
import com.harness.tool.search.SearxngSearchProvider;
import com.harness.tool.web.AuthorizedUrlContext;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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

/** Stable model-facing web search tool; provider selection stays inside {@link SearchRouter}. */
public class WebSearchTool implements Tool {

    public static final String TOOL_NAME = "web_search";
    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    private static final String DEFAULT_ENGINES = "bing,duckduckgo,brave,google,wikipedia";
    private static final int MAX_RESULT_LIMIT = 20;
    private static final Pattern LANGUAGE_PATTERN = Pattern.compile(
            "^(?:all|auto|[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*)$");

    private final ObjectMapper mapper;
    private final SearchRouter router;
    private final int resultLimit;
    private final String defaultLanguage;
    private final List<String> blockedDomains;
    private final SearxngSearchProvider legacySearxng;

    public WebSearchTool() {
        this(loadSettings(EnvConfig.get()));
    }

    private WebSearchTool(RuntimeSettings settings) {
        this(
                settings.mapper(),
                new SearchRouter(
                        createProviders(settings),
                        settings.strategy(),
                        Duration.ofSeconds(settings.timeoutSeconds())),
                settings.resultLimit(),
                settings.defaultLanguage(),
                settings.blockedDomains(),
                null);
        log.info("WebSearch initialized, strategy={}, providers={}",
                settings.strategy(), settings.providerIds());
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
        this(
                mapper,
                searxRouter(http, mapper, baseUrl, engines, minEngines, minScore),
                resultLimit,
                defaultLanguage,
                blockedDomains,
                new SearxngSearchProvider(http, mapper, baseUrl, engines, minEngines, minScore));
    }

    WebSearchTool(
            ObjectMapper mapper,
            SearchRouter router,
            int resultLimit,
            String defaultLanguage,
            List<String> blockedDomains
    ) {
        this(mapper, router, resultLimit, defaultLanguage, blockedDomains, null);
    }

    private WebSearchTool(
            ObjectMapper mapper,
            SearchRouter router,
            int resultLimit,
            String defaultLanguage,
            List<String> blockedDomains,
            SearxngSearchProvider legacySearxng
    ) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.router = Objects.requireNonNull(router, "router");
        if (resultLimit < 1 || resultLimit > MAX_RESULT_LIMIT) {
            throw new IllegalArgumentException(
                    "HARNESS_SEARCH_RESULT_LIMIT must be between 1 and " + MAX_RESULT_LIMIT);
        }
        this.resultLimit = resultLimit;
        this.defaultLanguage = normalizeLanguage(defaultLanguage, "HARNESS_TOOL_WEB_SEARCH_LANGUAGE");
        this.blockedDomains = normalizeDomains(blockedDomains);
        this.legacySearxng = legacySearxng;
    }

    public static boolean isEnabled(EnvConfig config) {
        return config.all().containsKey(EnvKey.SEARCH_ENABLED)
                ? config.getBool(EnvKey.SEARCH_ENABLED, true)
                : config.getBool(EnvKey.TOOL_WEB_SEARCH_ENABLED, true);
    }

    @Override
    public ToolSpec spec() {
        var properties = mapper.createObjectNode();
        properties.set("query", mapper.createObjectNode()
                .put("type", "string")
                .put("description", "Search query"));
        properties.set("language", mapper.createObjectNode()
                .put("type", "string")
                .put("pattern", LANGUAGE_PATTERN.pattern())
                .put("description", "Optional language such as zh-CN or en-US; all allows mixed results"));
        properties.set("after", mapper.createObjectNode()
                .put("type", "string")
                .put("format", "date-time")
                .put("description", "Optional earliest publication time in ISO-8601 format"));
        properties.set("before", mapper.createObjectNode()
                .put("type", "string")
                .put("format", "date-time")
                .put("description", "Optional latest publication time in ISO-8601 format"));
        properties.set("include_domains", stringArray("Optional domains to include"));
        properties.set("exclude_domains", stringArray("Optional domains to exclude"));
        return new ToolSpec(
                TOOL_NAME,
                "Search the web through configured providers. Provider fallback and result fusion are automatic; use returned URLs with web action=read for source details.",
                mapper.createObjectNode()
                        .put("type", "object")
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("properties", properties)
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("required", mapper.createArrayNode().add("query")),
                ToolCapability.RETRIEVAL);
    }

    @Override
    public String execute(JsonNode arguments) {
        return executeOutcome(arguments).content().modelContent();
    }

    @Override
    public ToolExecutionOutcome executeOutcome(JsonNode arguments) {
        String query = text(arguments, "query");
        if (query == null) {
            throw new ToolExecutionException(TOOL_NAME, "Missing required parameter: query");
        }
        String language = resolveLanguage(optionalLanguage(arguments));
        Set<String> excludes = new LinkedHashSet<>(stringSet(arguments, "exclude_domains"));
        excludes.addAll(blockedDomains);
        SearchRequest request = new SearchRequest(
                query,
                resultLimit,
                language,
                optionalInstant(arguments, "after"),
                optionalInstant(arguments, "before"),
                stringSet(arguments, "include_domains"),
                excludes);
        try {
            SearchResponse response = router.search(request);
            FormattedResponse formatted = format(response, query);
            return ToolExecutionOutcome.succeeded(
                    ToolOutput.text(mapper.writeValueAsString(formatted.envelope())),
                    formatted.resultStatus());
        } catch (ToolExecutionException exception) {
            throw exception;
        } catch (SearchProviderException exception) {
            throw new ToolExecutionException(TOOL_NAME, exception.getMessage(), exception);
        } catch (IOException exception) {
            throw new ToolExecutionException(TOOL_NAME, "Unable to encode search results", exception);
        }
    }

    FormattedResponse formatResponse(String json, String query) throws IOException {
        if (legacySearxng == null) {
            throw new IllegalStateException("Raw SearXNG formatting is only available on the compatibility constructor");
        }
        SearchRequest request = new SearchRequest(query, resultLimit, null, null, null, Set.of(), Set.of());
        try {
            return format(legacySearxng.parse(json, request, Duration.ZERO), query);
        } catch (SearchProviderException exception) {
            throw new ToolExecutionException(TOOL_NAME, exception.getMessage(), exception);
        }
    }

    HttpUrl buildRequestUrl(String query, String language) {
        if (legacySearxng == null) {
            throw new IllegalStateException("SearXNG request URLs are only available on the compatibility constructor");
        }
        return legacySearxng.buildRequestUrl(query, language);
    }

    private FormattedResponse format(SearchResponse response, String query) {
        Map<String, SearchResult> unique = new LinkedHashMap<>();
        Set<String> contributingProviders = new LinkedHashSet<>();
        for (SearchResult result : response.results()) {
            String url = SearchRouter.normalizeUrl(result.url());
            if (url == null || unique.containsKey(url) || isBlockedDomain(url)) {
                continue;
            }
            unique.put(url, result);
            for (String provider : result.provider().split(",")) {
                if (!provider.isBlank()) {
                    contributingProviders.add(provider.trim());
                }
            }
            if (unique.size() == resultLimit) {
                break;
            }
        }

        List<WebSearchData.Result> results = new ArrayList<>();
        int rank = 0;
        for (Map.Entry<String, SearchResult> entry : unique.entrySet()) {
            SearchResult result = entry.getValue();
            int outputRank = "router".equals(response.provider()) ? ++rank : result.providerRank();
            results.add(new WebSearchData.Result(
                    outputRank,
                    result.title(),
                    entry.getKey(),
                    result.snippet(),
                    result.provider(),
                    result.providerScore(),
                    result.category()));
        }
        AuthorizedUrlContext.authorizeAll(results.stream().map(WebSearchData.Result::url).toList());

        Map<String, Object> diagnostics = response.diagnostics();
        Map<String, Object> providerDetails = providerDetails(diagnostics, "searxng");
        boolean lowConfidence = Boolean.TRUE.equals(diagnostics.get("lowConfidence"))
                || Boolean.TRUE.equals(providerDetails.get("lowConfidence"))
                || (response.partialFailure() && contributingProviders.size() < 2);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("queriedProviders", diagnostics.getOrDefault("queriedProviders", List.of(response.provider())));
        meta.put("providerFailures", diagnostics.getOrDefault("providerFailures", Map.of()));
        meta.put("contributingProviders", List.copyOf(contributingProviders));
        meta.put("providerDurationsMs", diagnostics.getOrDefault("providerDurationsMs", Map.of()));
        meta.put("partialFailure", response.partialFailure());
        copyIfPresent(providerDetails, meta, "queriedEngines");
        copyIfPresent(providerDetails, meta, "unresponsiveEngines");
        copyIfPresent(providerDetails, meta, "contributingEngines");
        meta.put("upstreamResultCount", providerDetails.getOrDefault("upstreamResultCount", response.results().size()));
        meta.put("returnedResultCount", results.size());
        meta.put("lowConfidence", lowConfidence);
        if (lowConfidence) {
            Object notice = providerDetails.get("qualityNotice");
            meta.put("qualityNotice", notice == null
                    ? "Search coverage was degraded by one or more provider failures"
                    : notice);
        }

        boolean empty = results.isEmpty();
        ToolEnvelope<WebSearchData> envelope = new ToolEnvelope<>(
                empty ? ToolEnvelopeStatus.EMPTY : ToolEnvelopeStatus.SUCCESS,
                new WebSearchData(query, results),
                null,
                meta);
        return new FormattedResponse(envelope, empty ? ResultStatus.EMPTY : ResultStatus.AVAILABLE);
    }

    private boolean isBlockedDomain(String normalizedUrl) {
        HttpUrl parsed = HttpUrl.parse(normalizedUrl);
        if (parsed == null) {
            return false;
        }
        String host = parsed.host();
        return blockedDomains.stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
    }

    String resolveLanguage(String requestedLanguage) {
        return normalizeLanguage(
                requestedLanguage == null ? defaultLanguage : requestedLanguage,
                "language");
    }

    private String optionalLanguage(JsonNode arguments) {
        String language = text(arguments, "language");
        if (language == null) {
            return null;
        }
        if (!LANGUAGE_PATTERN.matcher(language).matches()) {
            throw new ToolExecutionException(TOOL_NAME, "Invalid language parameter: " + language);
        }
        return language;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode stringArray(String description) {
        return mapper.createObjectNode()
                .put("type", "array")
                .put("description", description)
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("items", mapper.createObjectNode().put("type", "string"));
    }

    private static SearchRouter searxRouter(
            OkHttpClient http,
            ObjectMapper mapper,
            String baseUrl,
            List<String> engines,
            int minEngines,
            double minScore
    ) {
        return new SearchRouter(
                List.of(new SearxngSearchProvider(http, mapper, baseUrl, engines, minEngines, minScore)),
                SearchRouter.Strategy.BALANCED,
                Duration.ofSeconds(15));
    }

    private static RuntimeSettings loadSettings(EnvConfig config) {
        int timeoutSeconds = config.getInt(EnvKey.SEARCH_TIMEOUT_SECONDS, 15);
        if (timeoutSeconds < 1 || timeoutSeconds > 120) {
            throw new IllegalArgumentException("HARNESS_SEARCH_TIMEOUT_SECONDS must be between 1 and 120");
        }
        int resultLimit = config.all().containsKey(EnvKey.SEARCH_RESULT_LIMIT)
                ? config.getInt(EnvKey.SEARCH_RESULT_LIMIT, 8)
                : config.getInt(EnvKey.TOOL_WEB_SEARCH_RESULT_LIMIT, 8);
        List<String> providerIds = config.getCommaList(EnvKey.SEARCH_PROVIDERS);
        if (providerIds.isEmpty()) {
            providerIds = List.of("searxng");
        }
        List<String> blockedDomains = config.all().containsKey(EnvKey.SEARCH_BLOCKED_DOMAINS)
                ? config.getCommaList(EnvKey.SEARCH_BLOCKED_DOMAINS)
                : config.getCommaList(EnvKey.TOOL_WEB_SEARCH_BLOCKED_DOMAINS);
        List<String> engines = config.getCommaList(EnvKey.TOOL_WEB_SEARCH_ENGINES);
        if (engines.isEmpty()) {
            engines = List.of(DEFAULT_ENGINES.split(","));
        }
        ObjectMapper mapper = new ObjectMapper();
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build();
        return new RuntimeSettings(
                mapper,
                http,
                providerIds.stream().map(value -> value.trim().toLowerCase(Locale.ROOT)).distinct().toList(),
                SearchRouter.Strategy.parse(config.getString(EnvKey.SEARCH_STRATEGY, "BALANCED")),
                timeoutSeconds,
                resultLimit,
                normalizeLanguage(config.getString(EnvKey.TOOL_WEB_SEARCH_LANGUAGE, ""),
                        "HARNESS_TOOL_WEB_SEARCH_LANGUAGE"),
                blockedDomains,
                config.all().containsKey(EnvKey.SEARCH_SEARXNG_URL)
                        ? config.getString(EnvKey.SEARCH_SEARXNG_URL, "")
                        : config.getString(EnvKey.TOOL_WEB_SEARCH_SEARXNG_URL, "http://localhost:8888"),
                engines,
                config.getInt(EnvKey.TOOL_WEB_SEARCH_MIN_ENGINES, 1),
                config.getDouble(EnvKey.TOOL_WEB_SEARCH_MIN_SCORE, 0.0),
                config.getString(EnvKey.SEARCH_BRAVE_URL, "https://api.search.brave.com/res/v1/web/search"),
                config.getString(EnvKey.SEARCH_BRAVE_API_KEY, ""),
                config.getString(EnvKey.SEARCH_EXA_URL, "https://api.exa.ai/search"),
                config.getString(EnvKey.SEARCH_EXA_API_KEY, ""));
    }

    private static List<SearchProvider> createProviders(RuntimeSettings settings) {
        List<SearchProvider> providers = new ArrayList<>();
        for (String id : settings.providerIds()) {
            providers.add(switch (id) {
                case "searxng" -> new SearxngSearchProvider(
                        settings.http(), settings.mapper(), settings.searxngUrl(), settings.engines(),
                        settings.minEngines(), settings.minScore());
                case "brave" -> new BraveSearchProvider(
                        settings.http(), settings.mapper(), settings.braveUrl(), settings.braveApiKey());
                case "exa" -> new ExaSearchProvider(
                        settings.http(), settings.mapper(), settings.exaUrl(), settings.exaApiKey());
                default -> throw new IllegalArgumentException("Unsupported search provider: " + id);
            });
        }
        return List.copyOf(providers);
    }

    private static String normalizeLanguage(String language, String label) {
        if (language == null || language.isBlank()
                || "all".equalsIgnoreCase(language) || "auto".equalsIgnoreCase(language)) {
            return null;
        }
        String value = language.trim();
        if (!LANGUAGE_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + label + ": " + language);
        }
        return value;
    }

    private static List<String> normalizeDomains(List<String> domains) {
        if (domains == null) {
            return List.of();
        }
        return domains.stream()
                .filter(Objects::nonNull)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }

    private static Set<String> stringSet(JsonNode arguments, String field) {
        JsonNode values = arguments.path(field);
        if (values.isMissingNode() || values.isNull()) {
            return Set.of();
        }
        if (!values.isArray() || values.size() > 20) {
            throw new ToolExecutionException(TOOL_NAME, field + " must be an array with at most 20 domains");
        }
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().isBlank()) {
                throw new ToolExecutionException(TOOL_NAME, field + " contains an invalid domain");
            }
            result.add(value.asText().trim().toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(result);
    }

    private static Instant optionalInstant(JsonNode arguments, String field) {
        String value = text(arguments, field);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new ToolExecutionException(TOOL_NAME,
                    field + " must be an ISO-8601 instant", exception);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() || value.asText().isBlank()
                ? null
                : value.asText().trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> providerDetails(Map<String, Object> diagnostics, String provider) {
        Object value = diagnostics.get("providerDiagnostics");
        if (value instanceof Map<?, ?> providers) {
            Object details = providers.get(provider);
            if (details instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        }
        return diagnostics;
    }

    private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String key) {
        if (from.containsKey(key)) {
            to.put(key, from.get(key));
        }
    }

    record FormattedResponse(ToolEnvelope<WebSearchData> envelope, ResultStatus resultStatus) {
    }

    private record RuntimeSettings(
            ObjectMapper mapper,
            OkHttpClient http,
            List<String> providerIds,
            SearchRouter.Strategy strategy,
            int timeoutSeconds,
            int resultLimit,
            String defaultLanguage,
            List<String> blockedDomains,
            String searxngUrl,
            List<String> engines,
            int minEngines,
            double minScore,
            String braveUrl,
            String braveApiKey,
            String exaUrl,
            String exaApiKey
    ) {
    }
}

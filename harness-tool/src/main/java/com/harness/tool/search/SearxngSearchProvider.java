package com.harness.tool.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

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

public final class SearxngSearchProvider implements SearchProvider {

    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final List<String> engines;
    private final int minEngines;
    private final double minScore;

    public SearxngSearchProvider(
            OkHttpClient http,
            ObjectMapper mapper,
            String baseUrl,
            List<String> engines,
            int minEngines,
            double minScore
    ) {
        this.http = Objects.requireNonNull(http, "http");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl").replaceAll("/+$", "");
        this.engines = normalizeEngines(engines);
        if (minEngines < 1) {
            throw new IllegalArgumentException("HARNESS_TOOL_WEB_SEARCH_MIN_ENGINES must be >= 1");
        }
        if (Double.isNaN(minScore) || minScore < 0) {
            throw new IllegalArgumentException("HARNESS_TOOL_WEB_SEARCH_MIN_SCORE must be >= 0");
        }
        this.minEngines = minEngines;
        this.minScore = minScore;
    }

    @Override
    public String id() {
        return "searxng";
    }

    @Override
    public SearchCapabilities capabilities() {
        return new SearchCapabilities(true, false, false);
    }

    @Override
    public SearchResponse search(SearchRequest request) {
        Request.Builder httpRequest = new Request.Builder()
                .url(buildRequestUrl(request.query(), request.language()))
                .header("Accept", "application/json");
        if (request.language() != null) {
            httpRequest.header("Accept-Language", request.language());
        }

        long started = System.nanoTime();
        try (Response response = http.newCall(httpRequest.get().build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new SearchProviderException(id(),
                        "SearXNG HTTP " + response.code() + ": " + abbreviate(body));
            }
            return parse(body, request, Duration.ofNanos(System.nanoTime() - started));
        } catch (IOException exception) {
            throw new SearchProviderException(id(), "SearXNG request failed: " + exception.getMessage(), exception);
        }
    }

    public HttpUrl buildRequestUrl(String query, String language) {
        HttpUrl endpoint = HttpUrl.parse(baseUrl + "/search");
        if (endpoint == null) {
            throw new SearchProviderException(id(), "Invalid SearXNG URL: " + baseUrl);
        }
        HttpUrl.Builder url = endpoint.newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("format", "json")
                .addQueryParameter("categories", "general")
                .addQueryParameter("engines", String.join(",", engines));
        if (language != null) {
            url.addQueryParameter("language", language);
        }
        return url.build();
    }

    public SearchResponse parse(String json, SearchRequest request, Duration duration) throws IOException {
        JsonNode root = mapper.readTree(json);
        List<EngineFailure> failures = parseFailures(root.path("unresponsive_engines"));
        JsonNode rawResults = root.path("results");
        if ((!rawResults.isArray() || rawResults.isEmpty()) && allEnginesFailed(failures)) {
            throw new SearchProviderException(id(), "All configured SearXNG engines failed: " + failures);
        }

        List<SearchResult> results = new ArrayList<>();
        Set<String> contributingEngines = new LinkedHashSet<>();
        if (rawResults.isArray()) {
            int rank = 0;
            for (JsonNode result : rawResults) {
                rank++;
                if (endorsement(result) < minEngines
                        || result.path("score").asDouble(0.0) < minScore) {
                    continue;
                }
                String url = text(result, "url");
                if (url == null) {
                    continue;
                }
                collectEngines(result, contributingEngines);
                results.add(new SearchResult(
                        defaultText(result, "title"),
                        url,
                        defaultText(result, "content"),
                        instant(result, "publishedDate"),
                        primaryEngine(result),
                        rank,
                        number(result, "score"),
                        text(result, "category")));
            }
        }

        Set<String> failedEngines = new LinkedHashSet<>();
        for (EngineFailure failure : failures) {
            String name = failure.engine().trim().toLowerCase(Locale.ROOT);
            if (engines.contains(name)) {
                failedEngines.add(name);
            }
        }
        boolean lowConfidence = failedEngines.size() >= 2
                || (results.size() >= 3 && contributingEngines.size() == 1 && engines.size() >= 2);
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("queriedEngines", engines);
        diagnostics.put("unresponsiveEngines", failures);
        diagnostics.put("contributingEngines", List.copyOf(contributingEngines));
        diagnostics.put("upstreamResultCount", rawResults.isArray() ? rawResults.size() : 0);
        diagnostics.put("lowConfidence", lowConfidence);
        if (lowConfidence) {
            diagnostics.put("qualityNotice", qualityNotice(failures, failedEngines, contributingEngines, results.size()));
        }
        return new SearchResponse(results, id(), duration, !failures.isEmpty(), diagnostics);
    }

    @Override
    public SearchHealth health() {
        return HttpUrl.parse(baseUrl + "/search") == null
                ? SearchHealth.unavailable("invalid endpoint")
                : SearchHealth.available();
    }

    private boolean allEnginesFailed(List<EngineFailure> failures) {
        Set<String> failed = new LinkedHashSet<>();
        failures.forEach(failure -> failed.add(failure.engine().trim().toLowerCase(Locale.ROOT)));
        return engines.stream().allMatch(failed::contains);
    }

    private static List<EngineFailure> parseFailures(JsonNode node) {
        List<EngineFailure> failures = new ArrayList<>();
        if (!node.isArray()) {
            return failures;
        }
        for (JsonNode failure : node) {
            if (failures.size() == 20) {
                break;
            }
            if (failure.isArray()) {
                failures.add(new EngineFailure(failure.path(0).asText("unknown"), failure.path(1).asText("unknown")));
            } else if (failure.isObject()) {
                failures.add(new EngineFailure(failure.path("engine").asText("unknown"), failure.path("error").asText("unknown")));
            } else if (failure.isTextual()) {
                failures.add(new EngineFailure(failure.asText(), "unknown"));
            }
        }
        return List.copyOf(failures);
    }

    private static void collectEngines(JsonNode result, Set<String> into) {
        JsonNode values = result.path("engines");
        if (values.isArray() && !values.isEmpty()) {
            values.forEach(value -> addEngine(value.asText(null), into));
        } else {
            addEngine(text(result, "engine"), into);
        }
    }

    private static String primaryEngine(JsonNode result) {
        String engine = text(result, "engine");
        if (engine != null) {
            return engine;
        }
        JsonNode values = result.path("engines");
        return values.isArray() && !values.isEmpty() ? values.get(0).asText("searxng") : "searxng";
    }

    private static void addEngine(String engine, Set<String> into) {
        if (engine != null && !engine.isBlank()) {
            into.add(engine.trim().toLowerCase(Locale.ROOT));
        }
    }

    private static int endorsement(JsonNode result) {
        JsonNode values = result.path("engines");
        return values.isArray() && !values.isEmpty() ? values.size() : 1;
    }

    private static String qualityNotice(
            List<EngineFailure> failures,
            Set<String> failedEngines,
            Set<String> contributingEngines,
            int resultCount
    ) {
        List<String> parts = new ArrayList<>();
        if (failedEngines.size() >= 2) {
            String detail = failures.stream()
                    .filter(failure -> failedEngines.contains(failure.engine().trim().toLowerCase(Locale.ROOT)))
                    .map(failure -> failure.engine() + ": " + failure.error())
                    .reduce((left, right) -> left + ", " + right).orElse("");
            parts.add(failedEngines.size() + " engines unresponsive: " + detail);
        }
        if (resultCount >= 3 && contributingEngines.size() == 1) {
            parts.add("all " + resultCount + " results came from 1 engine ("
                    + contributingEngines.iterator().next() + ")");
        }
        return String.join("; ", parts);
    }

    private static List<String> normalizeEngines(List<String> configured) {
        Objects.requireNonNull(configured, "engines");
        List<String> normalized = configured.stream()
                .filter(Objects::nonNull)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("At least one SearXNG engine must be configured");
        }
        return normalized;
    }

    static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || !value.isTextual() || value.asText().isBlank() ? null : value.asText().trim();
    }

    static String defaultText(JsonNode node, String name) {
        String value = text(node, name);
        return value == null ? "" : value;
    }

    static Double number(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value != null && value.isNumber() ? value.doubleValue() : null;
    }

    static Instant instant(JsonNode node, String name) {
        String value = text(node, name);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String abbreviate(String value) {
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }

    public record EngineFailure(String engine, String error) {
    }
}

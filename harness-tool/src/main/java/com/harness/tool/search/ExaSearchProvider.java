package com.harness.tool.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ExaSearchProvider implements SearchProvider {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final String endpoint;
    private final String apiKey;

    public ExaSearchProvider(OkHttpClient http, ObjectMapper mapper, String endpoint, String apiKey) {
        this.http = Objects.requireNonNull(http, "http");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    @Override
    public String id() {
        return "exa";
    }

    @Override
    public SearchCapabilities capabilities() {
        return new SearchCapabilities(false, true, true);
    }

    @Override
    public SearchResponse search(SearchRequest request) {
        if (apiKey.isEmpty()) {
            throw new SearchProviderException(id(), "Exa API key is not configured");
        }
        if (HttpUrl.parse(endpoint) == null) {
            throw new SearchProviderException(id(), "Invalid Exa Search URL");
        }
        ObjectNode payload = mapper.createObjectNode()
                .put("query", request.query())
                .put("numResults", request.limit())
                .put("type", "auto");
        if (request.after() != null) {
            payload.put("startPublishedDate", request.after().toString());
        }
        if (request.before() != null) {
            payload.put("endPublishedDate", request.before().toString());
        }
        if (!request.includeDomains().isEmpty()) {
            payload.set("includeDomains", mapper.valueToTree(request.includeDomains()));
        }
        if (!request.excludeDomains().isEmpty()) {
            payload.set("excludeDomains", mapper.valueToTree(request.excludeDomains()));
        }
        payload.set("contents", mapper.createObjectNode().put("highlights", true));
        Request httpRequest = new Request.Builder()
                .url(endpoint)
                .header("Accept", "application/json")
                .header("x-api-key", apiKey)
                .post(RequestBody.create(mapperValue(payload), JSON))
                .build();

        long started = System.nanoTime();
        try (Response response = http.newCall(httpRequest).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new SearchProviderException(id(), "Exa HTTP " + response.code());
            }
            JsonNode root = mapper.readTree(body);
            JsonNode values = root.path("results");
            List<SearchResult> results = new ArrayList<>();
            if (values.isArray()) {
                int rank = 0;
                for (JsonNode value : values) {
                    rank++;
                    String resultUrl = SearxngSearchProvider.text(value, "url");
                    if (resultUrl == null) {
                        continue;
                    }
                    results.add(new SearchResult(
                            SearxngSearchProvider.defaultText(value, "title"),
                            resultUrl,
                            snippet(value),
                            SearxngSearchProvider.instant(value, "publishedDate"),
                            id(), rank, null, "general"));
                }
            }
            return new SearchResponse(results, id(),
                    Duration.ofNanos(System.nanoTime() - started), false,
                    Map.of("upstreamResultCount", values.isArray() ? values.size() : 0));
        } catch (IOException exception) {
            throw new SearchProviderException(id(), "Exa request failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    public SearchHealth health() {
        return apiKey.isEmpty()
                ? SearchHealth.unavailable("API key not configured")
                : HttpUrl.parse(endpoint) == null
                        ? SearchHealth.unavailable("invalid endpoint")
                        : SearchHealth.available();
    }

    private String mapperValue(JsonNode value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw new SearchProviderException(id(), "Unable to encode Exa request", exception);
        }
    }

    private static String snippet(JsonNode value) {
        String summary = SearxngSearchProvider.text(value, "summary");
        if (summary != null) {
            return summary;
        }
        JsonNode highlights = value.path("highlights");
        if (highlights.isArray() && !highlights.isEmpty()) {
            return highlights.get(0).asText("");
        }
        String text = SearxngSearchProvider.defaultText(value, "text");
        return text.length() <= 1000 ? text : text.substring(0, 1000) + "...";
    }
}

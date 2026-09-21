package com.harness.tool.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class BraveSearchProvider implements SearchProvider {

    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final String endpoint;
    private final String apiKey;

    public BraveSearchProvider(OkHttpClient http, ObjectMapper mapper, String endpoint, String apiKey) {
        this.http = Objects.requireNonNull(http, "http");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    @Override
    public String id() {
        return "brave";
    }

    @Override
    public SearchCapabilities capabilities() {
        return new SearchCapabilities(true, true, false);
    }

    @Override
    public SearchResponse search(SearchRequest request) {
        if (apiKey.isEmpty()) {
            throw new SearchProviderException(id(), "Brave API key is not configured");
        }
        HttpUrl parsed = HttpUrl.parse(endpoint);
        if (parsed == null) {
            throw new SearchProviderException(id(), "Invalid Brave Search URL");
        }
        HttpUrl.Builder url = parsed.newBuilder()
                .addQueryParameter("q", request.query())
                .addQueryParameter("count", Integer.toString(request.limit()))
                .addQueryParameter("text_decorations", "false");
        if (request.language() != null) {
            url.addQueryParameter("search_lang", languageCode(request.language()));
        }
        if (request.after() != null && request.before() != null) {
            LocalDate after = request.after().atZone(ZoneOffset.UTC).toLocalDate();
            LocalDate before = request.before().atZone(ZoneOffset.UTC).toLocalDate();
            url.addQueryParameter("freshness", after + "to" + before);
        }
        Request httpRequest = new Request.Builder()
                .url(url.build())
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey)
                .get()
                .build();

        long started = System.nanoTime();
        try (Response response = http.newCall(httpRequest).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new SearchProviderException(id(), "Brave HTTP " + response.code());
            }
            JsonNode root = mapper.readTree(body);
            List<SearchResult> results = new ArrayList<>();
            JsonNode values = root.path("web").path("results");
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
                            SearxngSearchProvider.defaultText(value, "description"),
                            SearxngSearchProvider.instant(value, "page_age"),
                            id(), rank, null, "general"));
                }
            }
            return new SearchResponse(results, id(),
                    Duration.ofNanos(System.nanoTime() - started), false,
                    Map.of("upstreamResultCount", values.isArray() ? values.size() : 0));
        } catch (IOException exception) {
            throw new SearchProviderException(id(), "Brave request failed: " + exception.getMessage(), exception);
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

    private static String languageCode(String language) {
        int separator = language.indexOf('-');
        return separator < 0 ? language : language.substring(0, separator);
    }
}

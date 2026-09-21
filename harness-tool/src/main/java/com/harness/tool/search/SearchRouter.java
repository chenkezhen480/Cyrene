package com.harness.tool.search;

import okhttp3.HttpUrl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class SearchRouter {

    private static final double RRF_K = 60.0;
    private static final double CONSENSUS_BONUS = 0.01;

    public enum Strategy {
        FAST,
        BALANCED,
        DEEP;

        public static Strategy parse(String value) {
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Invalid HARNESS_SEARCH_STRATEGY: " + value, exception);
            }
        }
    }

    private final List<SearchProvider> providers;
    private final Strategy strategy;
    private final Duration timeout;

    public SearchRouter(List<SearchProvider> providers, Strategy strategy, Duration timeout) {
        this.providers = List.copyOf(Objects.requireNonNull(providers, "providers"));
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (this.providers.isEmpty()) {
            throw new IllegalArgumentException("At least one search provider must be configured");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Search timeout must be positive");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (SearchProvider provider : this.providers) {
            if (!ids.add(provider.id())) {
                throw new IllegalArgumentException("Duplicate search provider: " + provider.id());
            }
        }
    }

    public SearchResponse search(SearchRequest request) {
        List<SearchProvider> available = providers.stream()
                .filter(provider -> provider.health().isAvailable())
                .toList();
        if (available.isEmpty()) {
            throw new SearchProviderException("router", "No configured search provider is available");
        }

        long started = System.nanoTime();
        Map<String, String> failures = new LinkedHashMap<>();
        List<SearchResponse> responses = new ArrayList<>();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            switch (strategy) {
                case FAST -> fast(request, available, executor, responses, failures);
                case BALANCED -> balanced(request, available, executor, responses, failures);
                case DEEP -> deep(request, available, executor, responses, failures);
            }
        } finally {
            executor.shutdownNow();
        }
        if (responses.isEmpty()) {
            throw new SearchProviderException("router", "All search providers failed: " + failures);
        }

        List<SearchResult> results = fuse(responses, request.limit());
        boolean partialFailure = !failures.isEmpty()
                || responses.stream().anyMatch(SearchResponse::partialFailure);
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("strategy", strategy.name());
        diagnostics.put("configuredProviders", providers.stream().map(SearchProvider::id).toList());
        diagnostics.put("queriedProviders", responses.stream().map(SearchResponse::provider).toList());
        diagnostics.put("providerFailures", Map.copyOf(failures));
        Map<String, Long> durations = new LinkedHashMap<>();
        Map<String, Map<String, Object>> details = new LinkedHashMap<>();
        for (SearchResponse response : responses) {
            durations.put(response.provider(), response.duration().toMillis());
            details.put(response.provider(), response.diagnostics());
        }
        diagnostics.put("providerDurationsMs", durations);
        diagnostics.put("providerDiagnostics", details);
        diagnostics.put("lowConfidence", responses.size() == 1
                && Boolean.TRUE.equals(responses.get(0).diagnostics().get("lowConfidence")));
        return new SearchResponse(
                results,
                "router",
                Duration.ofNanos(System.nanoTime() - started),
                partialFailure,
                diagnostics);
    }

    private void fast(
            SearchRequest request,
            List<SearchProvider> available,
            ExecutorService executor,
            List<SearchResponse> responses,
            Map<String, String> failures
    ) {
        for (SearchProvider provider : available) {
            SearchResponse response = call(provider, request, executor, failures);
            if (response != null) {
                responses.add(response);
                if (enough(response, request.limit())) {
                    return;
                }
            }
        }
    }

    private void balanced(
            SearchRequest request,
            List<SearchProvider> available,
            ExecutorService executor,
            List<SearchResponse> responses,
            Map<String, String> failures
    ) {
        SearchResponse primary = call(available.get(0), request, executor, failures);
        if (primary != null) {
            responses.add(primary);
        }
        if (primary != null && enough(primary, request.limit())
                && !primary.partialFailure()
                && !Boolean.TRUE.equals(primary.diagnostics().get("lowConfidence"))) {
            return;
        }
        if (available.size() > 1) {
            SearchResponse hedge = call(available.get(1), request, executor, failures);
            if (hedge != null) {
                responses.add(hedge);
            }
        }
    }

    private void deep(
            SearchRequest request,
            List<SearchProvider> available,
            ExecutorService executor,
            List<SearchResponse> responses,
            Map<String, String> failures
    ) {
        Map<SearchProvider, CompletableFuture<SearchResponse>> futures = new LinkedHashMap<>();
        for (SearchProvider provider : available) {
            futures.put(provider, submit(provider, request, executor));
        }
        for (Map.Entry<SearchProvider, CompletableFuture<SearchResponse>> entry : futures.entrySet()) {
            try {
                responses.add(entry.getValue().join());
            } catch (CompletionException exception) {
                failures.put(entry.getKey().id(), failureMessage(exception));
            }
        }
    }

    private SearchResponse call(
            SearchProvider provider,
            SearchRequest request,
            ExecutorService executor,
            Map<String, String> failures
    ) {
        try {
            return submit(provider, request, executor).join();
        } catch (CompletionException exception) {
            failures.put(provider.id(), failureMessage(exception));
            return null;
        }
    }

    private CompletableFuture<SearchResponse> submit(
            SearchProvider provider,
            SearchRequest request,
            ExecutorService executor
    ) {
        return CompletableFuture.supplyAsync(() -> provider.search(request), executor)
                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static boolean enough(SearchResponse response, int requestedLimit) {
        int threshold = Math.min(requestedLimit, Math.max(3, (requestedLimit + 1) / 2));
        return response.results().size() >= threshold;
    }

    static List<SearchResult> fuse(List<SearchResponse> responses, int limit) {
        Map<String, Aggregate> byUrl = new LinkedHashMap<>();
        for (SearchResponse response : responses) {
            int fallbackRank = 0;
            for (SearchResult result : response.results()) {
                fallbackRank++;
                String url = normalizeUrl(result.url());
                if (url == null) {
                    continue;
                }
                int rank = result.providerRank() > 0 ? result.providerRank() : fallbackRank;
                Aggregate aggregate = byUrl.computeIfAbsent(url, ignored -> new Aggregate(result, url));
                aggregate.score += 1.0 / (RRF_K + rank);
                aggregate.providers.add(response.provider());
            }
        }
        byUrl.values().forEach(value -> value.score += CONSENSUS_BONUS * (value.providers.size() - 1));
        List<Aggregate> sorted = byUrl.values().stream()
                .sorted(Comparator.comparingDouble((Aggregate value) -> value.score).reversed()
                        .thenComparing(value -> value.url))
                .limit(limit)
                .toList();
        List<SearchResult> results = new ArrayList<>(sorted.size());
        for (Aggregate aggregate : sorted) {
            SearchResult selected = aggregate.selected;
            results.add(new SearchResult(
                    selected.title(),
                    aggregate.url,
                    selected.snippet(),
                    selected.publishedAt(),
                    String.join(",", aggregate.providers),
                    selected.providerRank(),
                    selected.providerScore(),
                    selected.category()));
        }
        return List.copyOf(results);
    }

    public static String normalizeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        HttpUrl parsed = HttpUrl.parse(rawUrl.trim());
        if (parsed == null) {
            return null;
        }
        HttpUrl.Builder builder = parsed.newBuilder().fragment(null);
        for (String name : parsed.queryParameterNames()) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith("utm_")
                    || "gclid".equals(lower)
                    || "fbclid".equals(lower)
                    || "msclkid".equals(lower)) {
                builder.removeAllQueryParameters(name);
            }
        }
        return builder.build().toString();
    }

    private static String failureMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private static final class Aggregate {
        private final SearchResult selected;
        private final String url;
        private final Set<String> providers = new LinkedHashSet<>();
        private double score;

        private Aggregate(SearchResult selected, String url) {
            this.selected = selected;
            this.url = url;
        }
    }
}

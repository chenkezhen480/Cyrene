package com.harness.tool.search;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class SearchRouterTest {

    @Test
    void balanced_fallsBackAndFusesDuplicateUrlsByRank() {
        SearchProvider primary = provider("primary", request -> response("primary", List.of(
                result("Primary", "https://example.com/shared#one", "primary", 1))));
        SearchProvider hedge = provider("hedge", request -> response("hedge", List.of(
                result("Shared", "https://example.com/shared#two", "hedge", 2),
                result("Other", "https://example.com/other", "hedge", 1))));
        SearchRouter router = new SearchRouter(
                List.of(primary, hedge), SearchRouter.Strategy.BALANCED, Duration.ofSeconds(1));

        SearchResponse response = router.search(request(6));

        assertThat(response.results()).hasSize(2);
        assertThat(response.results().get(0).url()).isEqualTo("https://example.com/shared");
        assertThat(response.results().get(0).provider()).isEqualTo("primary,hedge");
        assertThat(response.diagnostics().get("queriedProviders").toString())
                .contains("primary", "hedge");
    }

    @Test
    void deep_returnsHealthyResultsWhenAnotherProviderTimesOut() {
        SearchProvider slow = provider("slow", request -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return response("slow", List.of());
        });
        SearchProvider healthy = provider("healthy", request -> response("healthy", List.of(
                result("Good", "https://example.com/good", "healthy", 1))));
        SearchRouter router = new SearchRouter(
                List.of(slow, healthy), SearchRouter.Strategy.DEEP, Duration.ofMillis(40));

        SearchResponse response = router.search(request(5));

        assertThat(response.results()).singleElement()
                .extracting(SearchResult::title).isEqualTo("Good");
        assertThat(response.partialFailure()).isTrue();
        assertThat(response.diagnostics().get("providerFailures").toString()).contains("slow");
    }

    @Test
    void normalizeUrl_stripsTrackingParamsAndFragmentsWhilePreservingBusinessParams() {
        String raw = "https://example.com/docs?utm_source=twitter&utm_medium=cpc&gclid=abc&fbclid=def&msclkid=ghi&ref=sidebar&page=2#section";
        String normalized = SearchRouter.normalizeUrl(raw);
        assertThat(normalized).isEqualTo("https://example.com/docs?ref=sidebar&page=2");
    }

    @Test
    void fuse_fusesDuplicateUrlsWithDifferentTrackingParameters() {
        SearchResponse r1 = response("p1", List.of(
                result("Doc 1", "https://example.com/doc?utm_source=google", "p1", 1)));
        SearchResponse r2 = response("p2", List.of(
                result("Doc 1", "https://example.com/doc?utm_source=bing&gclid=123", "p2", 1)));

        List<SearchResult> fused = SearchRouter.fuse(List.of(r1, r2), 5);
        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).url()).isEqualTo("https://example.com/doc");
        assertThat(fused.get(0).provider()).isEqualTo("p1,p2");
    }

    private static SearchProvider provider(String id, Function<SearchRequest, SearchResponse> search) {
        return new SearchProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public SearchCapabilities capabilities() {
                return new SearchCapabilities(true, true, true);
            }

            @Override
            public SearchResponse search(SearchRequest request) {
                return search.apply(request);
            }

            @Override
            public SearchHealth health() {
                return SearchHealth.available();
            }
        };
    }

    private static SearchResponse response(String provider, List<SearchResult> results) {
        return new SearchResponse(results, provider, Duration.ofMillis(1), false, Map.of());
    }

    private static SearchResult result(String title, String url, String provider, int rank) {
        return new SearchResult(title, url, "snippet", null, provider, rank, null, "general");
    }

    private static SearchRequest request(int limit) {
        return new SearchRequest("query", limit, null, null, null, Set.of(), Set.of());
    }
}

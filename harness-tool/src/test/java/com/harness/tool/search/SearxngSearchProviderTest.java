package com.harness.tool.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearxngSearchProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parse_reportsResultsAndPartialEngineFailures() throws Exception {
        SearxngSearchProvider provider = provider(List.of("bing", "google"));

        SearchResponse response = provider.parse("""
                {
                  "results":[{"title":"Java 21","url":"https://example.com/java21","engine":"bing"}],
                  "unresponsive_engines":[["google","CAPTCHA"]]
                }
                """, request(), Duration.ofMillis(4));

        assertThat(response.results()).hasSize(1);
        assertThat(response.partialFailure()).isTrue();
        assertThat(response.diagnostics().get("unresponsiveEngines").toString()).contains("google", "CAPTCHA");
    }

    @Test
    void parse_rejectsWhenEveryConfiguredEngineFailed() {
        SearxngSearchProvider provider = provider(List.of("bing", "google"));

        assertThatThrownBy(() -> provider.parse("""
                {
                  "results":[],
                  "unresponsive_engines":[["bing","timeout"],["google","CAPTCHA"]]
                }
                """, request(), Duration.ZERO))
                .isInstanceOf(SearchProviderException.class)
                .hasMessageContaining("All configured SearXNG engines failed");
    }

    private static SearxngSearchProvider provider(List<String> engines) {
        return new SearxngSearchProvider(
                new OkHttpClient(), MAPPER, "http://127.0.0.1:8888", engines, 1, 0.0);
    }

    private static SearchRequest request() {
        return new SearchRequest("q", 8, null, null, null, Set.of(), Set.of());
    }
}

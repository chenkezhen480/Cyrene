package com.harness.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearxngResponseDiagnosticsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parse_chineseQueryFixture_countsResultsAndArrayFailures() throws Exception {
        String response = """
                {
                  "query": "Java 21 最新特性",
                  "results": [
                    {"title": "Java 21", "url": "https://example.com/java21"},
                    {"title": "JEP list", "url": "https://example.com/jeps"}
                  ],
                  "unresponsive_engines": [
                    ["google", "CAPTCHA"],
                    ["brave", "HTTP 429"]
                  ]
                }
                """;

        SearxngResponseDiagnostics.Snapshot diagnostics =
                SearxngResponseDiagnostics.parse(MAPPER.readTree(response));

        assertThat(diagnostics.resultCount()).isEqualTo(2);
        assertThat(diagnostics.unresponsiveEngines()).containsExactly(
                new SearxngResponseDiagnostics.EngineFailure("google", "CAPTCHA"),
                new SearxngResponseDiagnostics.EngineFailure("brave", "HTTP 429"));
    }

    @Test
    void parse_englishQueryFixture_acceptsObjectFailures() throws Exception {
        String response = """
                {
                  "query": "latest Java release",
                  "results": [],
                  "unresponsive_engines": [
                    {"engine": "bing", "error": "timeout"}
                  ]
                }
                """;

        SearxngResponseDiagnostics.Snapshot diagnostics =
                SearxngResponseDiagnostics.parse(MAPPER.readTree(response));

        assertThat(diagnostics.resultCount()).isZero();
        assertThat(diagnostics.unresponsiveEngines()).containsExactly(
                new SearxngResponseDiagnostics.EngineFailure("bing", "timeout"));
    }

    @Test
    void allConfiguredEnginesFailed_requiresEveryEngineAndNoResults() throws Exception {
        SearxngResponseDiagnostics.Snapshot allFailed =
                SearxngResponseDiagnostics.parse(MAPPER.readTree("""
                        {
                          "results":[],
                          "unresponsive_engines":[
                            ["bing","timeout"],
                            ["google","CAPTCHA"]
                          ]
                        }
                        """));
        SearxngResponseDiagnostics.Snapshot partialFailure =
                SearxngResponseDiagnostics.parse(MAPPER.readTree("""
                        {
                          "results":[],
                          "unresponsive_engines":[["google","CAPTCHA"]]
                        }
                        """));

        assertThat(SearxngResponseDiagnostics.allConfiguredEnginesFailed(
                allFailed, java.util.List.of("bing", "google"))).isTrue();
        assertThat(SearxngResponseDiagnostics.allConfiguredEnginesFailed(
                partialFailure, java.util.List.of("bing", "google"))).isFalse();
    }
}

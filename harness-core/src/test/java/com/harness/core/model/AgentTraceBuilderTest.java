package com.harness.core.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTraceBuilderTest {

    @Test
    void builder_defaults() {
        var before = Instant.now();
        var trace = AgentTrace.builder().build();
        var after = Instant.now();

        assertThat(trace.traceId()).isNotNull().isNotEmpty();
        assertThat(trace.timestamp()).isBetween(before, after);
        assertThat(trace.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(trace.steps()).isEmpty();
        assertThat(trace.inputAttachments()).isEmpty();
        assertThat(trace.ragHits()).isEmpty();
        assertThat(trace.metadata()).isEmpty();
        assertThat(trace.userConfirmed()).isFalse();
    }

    @Test
    void builder_chaining_setsAllFields() {
        var trace = AgentTrace.builder()
                .traceId("custom-id")
                .timestamp(Instant.EPOCH)
                .userId("user1")
                .sessionId("sess1")
                .inputText("hello")
                .intent("chat")
                .llmModel("gpt-4")
                .promptVersion("v1")
                .finalOutput("response")
                .riskLevel(RiskLevel.HIGH)
                .userConfirmed(true)
                .totalDurationMs(1500)
                .totalTokens(500)
                .metadata(Map.of("key", "value"))
                .build();

        assertThat(trace.traceId()).isEqualTo("custom-id");
        assertThat(trace.timestamp()).isEqualTo(Instant.EPOCH);
        assertThat(trace.userId()).isEqualTo("user1");
        assertThat(trace.sessionId()).isEqualTo("sess1");
        assertThat(trace.inputText()).isEqualTo("hello");
        assertThat(trace.intent()).isEqualTo("chat");
        assertThat(trace.llmModel()).isEqualTo("gpt-4");
        assertThat(trace.promptVersion()).isEqualTo("v1");
        assertThat(trace.finalOutput()).isEqualTo("response");
        assertThat(trace.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(trace.userConfirmed()).isTrue();
        assertThat(trace.totalDurationMs()).isEqualTo(1500);
        assertThat(trace.totalTokens()).isEqualTo(500);
        assertThat(trace.metadata()).containsEntry("key", "value");
    }
}

package com.harness.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentResultTest {

    @Test
    void success_createsResultWithoutConfirmation() {
        var trace = AgentTrace.builder().build();
        var result = AgentResult.success("output text", trace, List.of());

        assertThat(result.output()).isEqualTo("output text");
        assertThat(result.requiresConfirmation()).isFalse();
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.trace()).isSameAs(trace);
        assertThat(result.steps()).isEmpty();
    }

    @Test
    void needConfirmation_createsResultWithConfirmation() {
        var trace = AgentTrace.builder().build();
        var result = AgentResult.needConfirmation("risky output", RiskLevel.HIGH, trace, List.of());

        assertThat(result.output()).isEqualTo("risky output");
        assertThat(result.requiresConfirmation()).isTrue();
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.HIGH);
    }
}

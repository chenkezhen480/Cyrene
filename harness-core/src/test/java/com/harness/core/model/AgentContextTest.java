package com.harness.core.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentContextTest {

    @Test
    void empty_returnsDefaultBlockingMode() {
        var ctx = AgentContext.empty();
        assertThat(ctx.outputMode()).isEqualTo("blocking");
        assertThat(ctx.isStreaming()).isFalse();
        assertThat(ctx.userId()).isNull();
        assertThat(ctx.enableThinking()).isNull();
    }

    @Test
    void of_streamingMode() {
        var ctx = AgentContext.of(Map.of("outputMode", "streaming"));
        assertThat(ctx.isStreaming()).isTrue();
        assertThat(ctx.outputMode()).isEqualTo("streaming");
    }

    @Test
    void of_nullData_createsEmptyContext() {
        var ctx = AgentContext.of(null);
        assertThat(ctx.data()).isEmpty();
        assertThat(ctx.isStreaming()).isFalse();
    }

    @Test
    void userId_absent_returnsNull() {
        var ctx = AgentContext.empty();
        assertThat(ctx.userId()).isNull();
    }

    @Test
    void userId_present_returnsValue() {
        var ctx = AgentContext.of(Map.of("userId", "user123"));
        assertThat(ctx.userId()).isEqualTo("user123");
    }

    @Test
    void enableThinking_absent_returnsNull() {
        var ctx = AgentContext.empty();
        assertThat(ctx.enableThinking()).isNull();
    }

    @Test
    void enableThinking_booleanTrue() {
        var ctx = AgentContext.of(Map.of("enableThinking", true));
        assertThat(ctx.enableThinking()).isTrue();
    }

    @Test
    void enableThinking_booleanFalse() {
        var ctx = AgentContext.of(Map.of("enableThinking", false));
        assertThat(ctx.enableThinking()).isFalse();
    }

    @Test
    void enableThinking_stringTrue() {
        var ctx = AgentContext.of(Map.of("enableThinking", "true"));
        assertThat(ctx.enableThinking()).isTrue();
    }

    @Test
    void enableThinking_stringFalse() {
        var ctx = AgentContext.of(Map.of("enableThinking", "false"));
        assertThat(ctx.enableThinking()).isFalse();
    }
}

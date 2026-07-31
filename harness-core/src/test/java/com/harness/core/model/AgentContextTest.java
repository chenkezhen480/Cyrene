package com.harness.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void graphRequestContext_usesExplicitGraphSpace() {
        AgentContext context = AgentContext.of(Map.of(
                "graphRequestContext", Map.of(
                        "graphId", "graph-1",
                        "schemaId", "project-graph",
                        "subjectIds", List.of("project-1"),
                        "allowedQueryIds", List.of("anchored-neighborhood")
                )
        ));

        GraphRequestContext graphContext = context.graphRequestContext();

        assertThat(graphContext.graphId()).isEqualTo("graph-1");
        assertThat(graphContext.schemaId()).isEqualTo("project-graph");
        assertThat(graphContext.subjectIds()).containsExactly("project-1");
    }

    @Test
    void graphRequestContext_requiresGraphId() {
        AgentContext context = AgentContext.of(Map.of(
                "graphRequestContext", Map.of(
                        "schemaId", "project-graph",
                        "subjectIds", List.of("project-1")
                )
        ));

        assertThatThrownBy(context::graphRequestContext)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("graphId");
    }

    @Test
    void withClearedCredentials_removesGraphScope() {
        AgentContext context = AgentContext.of(Map.of(
                "graphRequestContext", Map.of(
                        "schemaId", "project-graph",
                        "subjectIds", List.of("project-1")
                )
        ));

        AgentContext isolated = context.withClearedCredentials();

        assertThat(isolated.graphRequestContext()).isNull();
        assertThat(isolated.data()).doesNotContainKey(AgentContext.KEY_NEEDS_GRAPH_KNOWLEDGE);
    }
}

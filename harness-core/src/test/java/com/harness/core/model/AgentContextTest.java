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
        assertThat(ctx.tenantId()).isEqualTo(AgentContext.DEFAULT_TENANT_ID);
        assertThat(ctx.enableThinking()).isNull();
    }

    @Test
    void of_streamingMode() {
        var ctx = AgentContext.of(Map.of("outputMode", "streaming"));
        assertThat(ctx.isStreaming()).isTrue();
        assertThat(ctx.outputMode()).isEqualTo("streaming");
    }

    @Test
    void of_audioMode_isStreamingVoiceOutput() {
        var ctx = AgentContext.of(Map.of("outputMode", "audio"));

        assertThat(ctx.isStreaming()).isTrue();
        assertThat(ctx.isVoiceOutput()).isTrue();
        assertThat(ctx.outputMode()).isEqualTo("audio");
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
    void tenantId_usesCallerValueWhenPresent() {
        var ctx = AgentContext.of(Map.of("tenantId", " enterprise-001 "));

        assertThat(ctx.tenantId()).isEqualTo("enterprise-001");
    }

    @Test
    void tenantId_rejectsOversizedValue() {
        var ctx = AgentContext.of(Map.of("tenantId", "t".repeat(129)));

        assertThatThrownBy(ctx::tenantId)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("128");
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
    void graphRequestContext_allowsGraphSpaceWithoutSubjectScope() {
        AgentContext context = AgentContext.of(Map.of(
                "graphRequestContext", Map.of(
                        "graphId", "graph-1",
                        "schemaId", "project-graph"
                )
        ));

        GraphRequestContext graphContext = context.graphRequestContext();

        assertThat(graphContext.graphId()).isEqualTo("graph-1");
        assertThat(graphContext.schemaId()).isEqualTo("project-graph");
        assertThat(graphContext.subjectIds()).isEmpty();
        assertThat(graphContext.hasSubjectScope()).isFalse();
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
    void knowledgeRequestContext_usesTrustedCollectionAndDocumentScope() {
        AgentContext context = AgentContext.of(Map.of(
                "knowledgeRequestContext", Map.of(
                        "collection", "tenant-manuals",
                        "allowedDocumentIds", List.of("document-1", "document-2")
                )
        ));

        KnowledgeRequestContext knowledgeContext = context.knowledgeRequestContext();

        assertThat(knowledgeContext.collection()).isEqualTo("tenant-manuals");
        assertThat(knowledgeContext.allowsDocument("document-1")).isTrue();
        assertThat(knowledgeContext.allowsDocument("document-3")).isFalse();
    }

    @Test
    void knowledgeRequestContext_requiresCollection() {
        AgentContext context = AgentContext.of(Map.of(
                "knowledgeRequestContext", Map.of(
                        "allowedDocumentIds", List.of("document-1")
                )
        ));

        assertThatThrownBy(context::knowledgeRequestContext)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collection");
    }

    @Test
    void withClearedCredentials_removesTrustedRetrievalScopes() {
        AgentContext context = AgentContext.of(Map.of(
                "graphRequestContext", Map.of(
                        "schemaId", "project-graph",
                        "subjectIds", List.of("project-1")
                ),
                "knowledgeRequestContext", Map.of("collection", "tenant-manuals")
        ));

        AgentContext isolated = context.withClearedCredentials();

        assertThat(isolated.graphRequestContext()).isNull();
        assertThat(isolated.knowledgeRequestContext()).isNull();
        assertThat(isolated.data()).doesNotContainKey(AgentContext.KEY_NEEDS_GRAPH_KNOWLEDGE);
    }
}

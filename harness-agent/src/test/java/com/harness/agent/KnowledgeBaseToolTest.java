package com.harness.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.context.ContextBuilder;
import com.harness.agent.context.KnowledgeAccessService;
import com.harness.core.model.KnowledgeRequestContext;
import com.harness.core.model.ReActStep;
import com.harness.core.model.ToolResult;
import com.harness.tool.rag.RagRetriever;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeBaseToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearThreadLocals() {
        ReActStep.clearCurrentSteps();
        ToolResult.clearCurrentStatus();
        KnowledgeAccessService.clearCurrentContext();
    }

    @Test
    void returnsStableJsonFieldsForAcceptedHits() throws Exception {
        ContextBuilder contextBuilder = mock(ContextBuilder.class);
        when(contextBuilder.maxSearchLimit()).thenReturn(5);
        when(contextBuilder.defaultCollection()).thenReturn("default");
        when(contextBuilder.buildRagForTool("上传文件限制是什么？", "default", 5))
                .thenReturn(new ContextBuilder.ContextResult(
                        List.of(new RagRetriever.RagDocument(
                                "chunk-4", "单个上传文件最大为 20 MB。", "manual.md", 0.86,
                                Map.of(
                                        "document_id", "document-1",
                                        "heading_path", List.of("上传文件", "大小限制")),
                                4)),
                        Map.of(
                                "top_score", "0.86",
                                "best_observed_score", "0.91",
                                "observed_candidate_count", "5",
                                "query_count", "1",
                                "provider", "test",
                                "rerank_ms", "28")));
        KnowledgeBaseTool tool = new KnowledgeBaseTool(
                contextBuilder, null,
                new RetrievalEscalationPolicy(0.3, 0.7), objectMapper);

        String output = tool.execute(objectMapper.createObjectNode()
                .put("query", "上传文件限制是什么？"));

        var envelope = objectMapper.readTree(output);
        assertThat(envelope.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(envelope.path("pageInfo").isNull()).isTrue();
        assertThat(envelope.at("/data/hits")).hasSize(1);
        assertThat(envelope.at("/data/hits/0/chunkId").asText()).isEqualTo("chunk-4");
        assertThat(envelope.at("/data/hits/0/documentId").asText()).isEqualTo("document-1");
        assertThat(envelope.at("/data/hits/0/fileName").asText()).isEqualTo("manual.md");
        assertThat(envelope.at("/data/hits/0/chunkIndex").asInt()).isEqualTo(4);
        assertThat(envelope.at("/data/hits/0/headingPath/1").asText()).isEqualTo("大小限制");
        assertThat(envelope.at("/data/hits/0/reingestRequired").asBoolean()).isFalse();
        assertThat(envelope.at("/data/hits/0/score").asDouble()).isEqualTo(0.86);
        assertThat(envelope.at("/data/hits/0/content").asText())
                .isEqualTo("单个上传文件最大为 20 MB。");
        assertThat(envelope.at("/meta/queryCount").asInt()).isEqualTo(1);
        assertThat(envelope.at("/meta/provider").asText()).isEqualTo("test");
        assertThat(envelope.at("/meta/rerankMs").asLong()).isEqualTo(28);
        assertThat(ToolResult.consumeCurrentStatus()).isEqualTo(ToolResult.ResultStatus.SUCCESS);
    }

    @Test
    void returnsEmptyEnvelopeWithoutEchoingTheQuery() throws Exception {
        ContextBuilder contextBuilder = mock(ContextBuilder.class);
        when(contextBuilder.maxSearchLimit()).thenReturn(5);
        when(contextBuilder.defaultCollection()).thenReturn("default");
        when(contextBuilder.buildRagForTool("confidential query", "default", 5))
                .thenReturn(new ContextBuilder.ContextResult(
                        List.of(),
                        Map.of(
                                "top_score", "0.0",
                                "best_observed_score", "0.1",
                                "observed_candidate_count", "2",
                                "query_count", "1",
                                "provider", "test",
                                "rerank_ms", "3")));
        KnowledgeBaseTool tool = new KnowledgeBaseTool(
                contextBuilder, null,
                new RetrievalEscalationPolicy(0.3, 0.7), objectMapper);

        String output = tool.execute(objectMapper.createObjectNode()
                .put("query", "confidential query"));

        var envelope = objectMapper.readTree(output);
        assertThat(envelope.path("status").asText()).isEqualTo("EMPTY");
        assertThat(envelope.at("/data/hits")).isEmpty();
        assertThat(output).doesNotContain("confidential query");
        assertThat(ToolResult.consumeCurrentStatus()).isEqualTo(ToolResult.ResultStatus.EMPTY);
    }

    @Test
    void trustedScopeHidesCollectionAndSearchHasFocusedSchema() {
        ContextBuilder contextBuilder = mock(ContextBuilder.class);
        when(contextBuilder.maxSearchLimit()).thenReturn(5);
        KnowledgeAccessService access = new KnowledgeAccessService(contextBuilder, 2);
        KnowledgeBaseTool tool = new KnowledgeBaseTool(
                access, null, new RetrievalEscalationPolicy(0.3, 0.7), objectMapper);
        KnowledgeAccessService.setCurrentContext(
                "tenant-1",
                new KnowledgeRequestContext("tenant-manuals", java.util.Set.of()));

        var schema = tool.spec().parameters();

        assertThat(schema.at("/properties/collection").isMissingNode()).isTrue();
        assertThat(schema.at("/properties/action").isMissingNode()).isTrue();
        assertThat(schema.at("/properties/documentId").isMissingNode()).isTrue();
        assertThat(schema.at("/required/0").asText()).isEqualTo("query");
    }
}

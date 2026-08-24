package com.harness.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.context.ContextBuilder;
import com.harness.agent.context.KnowledgeAccessService;
import com.harness.core.model.KnowledgeRequestContext;
import com.harness.core.model.ToolResult;
import com.harness.tool.rag.RagRetriever;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeContextReadToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearThreadLocals() {
        ToolResult.clearCurrentStatus();
        KnowledgeAccessService.clearCurrentContext();
    }

    @Test
    void usesTrustedCollectionAndReturnsStableOrderedWindow() throws Exception {
        ContextBuilder contextBuilder = mock(ContextBuilder.class);
        when(contextBuilder.readContext("tenant-manuals", "document-1", 4, 1, 1))
                .thenReturn(List.of(
                        ragDocument("chunk-3", "前置定义", 3),
                        ragDocument("chunk-4", "命中内容", 4),
                        ragDocument("chunk-5", "后续步骤", 5)));
        KnowledgeContextReadTool tool = tool(contextBuilder, 2);
        KnowledgeAccessService.setCurrentContext(
                "tenant-1",
                new KnowledgeRequestContext(
                        "tenant-manuals", java.util.Set.of("document-1")));

        String output = tool.execute(objectMapper.createObjectNode()
                .put("collection", "model-attempted-collection")
                .put("documentId", "document-1")
                .put("anchorChunkIndex", 4)
                .put("before", 1)
                .put("after", 1));

        var envelope = objectMapper.readTree(output);
        assertThat(envelope.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(envelope.at("/data/documentId").asText()).isEqualTo("document-1");
        assertThat(envelope.at("/data/chunks")).hasSize(3);
        assertThat(envelope.at("/data/chunks/0/chunkIndex").asInt()).isEqualTo(3);
        assertThat(envelope.at("/data/chunks/2/chunkIndex").asInt()).isEqualTo(5);
        assertThat(envelope.at("/meta/collection").asText()).isEqualTo("tenant-manuals");
        verify(contextBuilder).readContext("tenant-manuals", "document-1", 4, 1, 1);
    }

    @Test
    void defaultsToOneChunkOnEachSide() throws Exception {
        ContextBuilder contextBuilder = mock(ContextBuilder.class);
        when(contextBuilder.defaultCollection()).thenReturn("default");
        when(contextBuilder.readContext("default", "document-1", 4, 1, 1))
                .thenReturn(List.of(ragDocument("chunk-4", "命中内容", 4)));
        KnowledgeContextReadTool tool = tool(contextBuilder, 2);

        String output = tool.execute(objectMapper.createObjectNode()
                .put("documentId", "document-1")
                .put("anchorChunkIndex", 4));

        assertThat(objectMapper.readTree(output).at("/meta/before").asInt()).isEqualTo(1);
        assertThat(objectMapper.readTree(output).at("/meta/after").asInt()).isEqualTo(1);
        verify(contextBuilder).readContext("default", "document-1", 4, 1, 1);
    }

    @Test
    void rejectsDocumentOutsideTrustedScope() {
        ContextBuilder contextBuilder = mock(ContextBuilder.class);
        KnowledgeContextReadTool tool = tool(contextBuilder, 2);
        KnowledgeAccessService.setCurrentContext(
                "tenant-1",
                new KnowledgeRequestContext(
                        "tenant-manuals", java.util.Set.of("document-1")));

        assertThatThrownBy(() -> tool.execute(objectMapper.createObjectNode()
                .put("documentId", "document-2")
                .put("anchorChunkIndex", 4)))
                .hasMessageContaining("trusted knowledge request scope");
    }

    @Test
    void explainsThatLegacyHitsNeedReingest() {
        KnowledgeContextReadTool tool = tool(mock(ContextBuilder.class), 2);

        assertThatThrownBy(() -> tool.execute(objectMapper.createObjectNode()
                .put("anchorChunkIndex", 4)))
                .hasMessageContaining("re-ingest");
    }

    @Test
    void trustedScopeHidesCollectionAndExposesWindowLimit() {
        KnowledgeContextReadTool tool = tool(mock(ContextBuilder.class), 2);
        KnowledgeAccessService.setCurrentContext(
                "tenant-1",
                new KnowledgeRequestContext("tenant-manuals", java.util.Set.of()));

        var schema = tool.spec().parameters();

        assertThat(schema.at("/properties/collection").isMissingNode()).isTrue();
        assertThat(schema.at("/properties/before/maximum").asInt()).isEqualTo(2);
        assertThat(schema.at("/properties/after/maximum").asInt()).isEqualTo(2);
        assertThat(schema.at("/required/0").asText()).isEqualTo("documentId");
        assertThat(schema.at("/required/1").asText()).isEqualTo("anchorChunkIndex");
    }

    @Test
    void rejectsWindowBeyondConfiguredMaximum() {
        KnowledgeContextReadTool tool = tool(mock(ContextBuilder.class), 2);

        assertThatThrownBy(() -> tool.execute(objectMapper.createObjectNode()
                .put("documentId", "document-1")
                .put("anchorChunkIndex", 4)
                .put("before", 3)))
                .hasMessageContaining("before must be between 0 and 2");
    }

    private KnowledgeContextReadTool tool(ContextBuilder contextBuilder, int windowMax) {
        return new KnowledgeContextReadTool(
                new KnowledgeAccessService(contextBuilder, windowMax), objectMapper);
    }

    private static RagRetriever.RagDocument ragDocument(
            String chunkId,
            String content,
            int chunkIndex
    ) {
        return new RagRetriever.RagDocument(
                chunkId,
                content,
                "manual.md",
                0.0,
                Map.of(
                        "document_id", "document-1",
                        "heading_path", List.of("上传文件", "大小限制")),
                chunkIndex);
    }
}

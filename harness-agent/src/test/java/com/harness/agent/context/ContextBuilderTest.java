package com.harness.agent.context;

import com.harness.tool.rag.VectorStore;
import com.harness.tool.rerank.Reranker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextBuilderTest {

    @Test
    void buildRagForToolPreservesScoreEvidenceWithoutRejectedDocuments() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.providerName()).thenReturn("test");
        when(vectorStore.searchTextWithEvidence(anyString(), anyString(), anyInt()))
                .thenReturn(new VectorStore.SearchResult(List.of(), 0.45, 5));
        ContextBuilder contextBuilder = new ContextBuilder(vectorStore, new Reranker(null));

        ContextBuilder.ContextResult result = contextBuilder.buildRagForTool("standalone query");

        assertThat(result.hasContext()).isFalse();
        assertThat(result.ragHitIds()).isEmpty();
        assertThat(result.bestObservedScore()).isEqualTo(0.45);
        assertThat(result.observedCandidateCount()).isEqualTo(5);
    }

    @Test
    void buildRagForToolPreservesStableAnchorsWithoutImplicitWindowRead() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.providerName()).thenReturn("test");
        when(vectorStore.searchTextWithEvidence(anyString(), anyString(), anyInt()))
                .thenReturn(new VectorStore.SearchResult(
                        List.of(
                                new VectorStore.Document(
                                        "question-chunk",
                                        "单个上传文件的大小限制是多少？",
                                        "upload-guide.md",
                                        0.92,
                                        Map.of("document_id", "upload-guide", "heading_path", List.of("上传文件")),
                                        null,
                                        3),
                                new VectorStore.Document(
                                        "answer-chunk",
                                        "单个上传文件最大为 20 MB，超过限制会返回明确错误。",
                                        "upload-guide.md",
                                        0.88,
                                        Map.of("document_id", "upload-guide", "heading_path", List.of("上传文件", "大小限制")),
                                        null,
                                        4)),
                        0.92,
                        2));
        ContextBuilder contextBuilder = new ContextBuilder(vectorStore, new Reranker(null));

        ContextBuilder.ContextResult result = contextBuilder.buildRagForTool(
                "单个上传文件的大小限制是多少？");

        assertThat(result.ragHitIds()).containsExactly("question-chunk", "answer-chunk");
        assertThat(result.documents()).extracting(document -> document.content())
                .containsExactly(
                        "单个上传文件的大小限制是多少？",
                        "单个上传文件最大为 20 MB，超过限制会返回明确错误。");
        assertThat(result.topScore()).isEqualTo(0.92);
        assertThat(result.documents()).extracting(document -> document.chunkIndex())
                .containsExactly(3, 4);
        assertThat(result.documents().get(1).metadata())
                .containsEntry("document_id", "upload-guide");
        assertThat(result.metadata())
                .containsEntry("provider", "test")
                .containsEntry("query_count", "1");
        verify(vectorStore, never()).readDocumentWindow(
                anyString(), anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void readContextDelegatesOneExplicitBoundedWindow() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.readDocumentWindow("tenant-manuals", "document-1", 4, 1, 1))
                .thenReturn(List.of(new VectorStore.Document(
                        "chunk-4",
                        "单个上传文件最大为 20 MB。",
                        "upload-guide.md",
                        0.0,
                        Map.of("document_id", "document-1"),
                        null,
                        4)));
        ContextBuilder contextBuilder = new ContextBuilder(vectorStore, new Reranker(null));

        List<com.harness.tool.rag.RagRetriever.RagDocument> documents =
                contextBuilder.readContext("tenant-manuals", "document-1", 4, 1, 1);

        assertThat(documents).singleElement().satisfies(document -> {
            assertThat(document.id()).isEqualTo("chunk-4");
            assertThat(document.chunkIndex()).isEqualTo(4);
        });
    }
}

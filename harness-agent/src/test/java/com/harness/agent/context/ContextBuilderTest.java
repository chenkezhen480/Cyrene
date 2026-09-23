package com.harness.agent.context;

import com.harness.tool.rag.VectorStore;
import com.harness.tool.rerank.Reranker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextBuilderTest {

    @Test
    void searchDocumentRevisionsDelegatesToVectorStoreAndReranks() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.searchDocumentRevisions(
                "tenant-manuals", "upload limit", com.harness.core.knowledge.KnowledgeSearchOptions.defaults(5), Map.of("upload-guide", "rev-1")))
                .thenReturn(new VectorStore.SearchResult(
                        List.of(new VectorStore.Document(
                                "answer-chunk",
                                "单个上传文件最大为 20 MB，超过限制会返回明确错误。",
                                "upload-guide.md",
                                0.9,
                                Map.of("document_id", "upload-guide", "revision_id", "rev-1"),
                                null,
                                4)),
                        0.9,
                        1));
        ContextBuilder contextBuilder = new ContextBuilder(vectorStore, new Reranker(null));

        ContextBuilder.ContextResult result = contextBuilder.searchDocumentRevisions(
                "upload limit", "tenant-manuals", 5, Map.of("upload-guide", "rev-1"));

        assertThat(result.documents()).singleElement().satisfies(document -> {
            assertThat(document.id()).isEqualTo("answer-chunk");
            assertThat(document.chunkIndex()).isEqualTo(4);
            assertThat(document.metadata()).containsEntry("document_id", "upload-guide");
        });
        assertThat(result.metadata()).containsEntry("collection", "tenant-manuals");
    }

    @Test
    void readContextDelegatesOneExplicitBoundedWindow() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.readDocumentWindow(
                "tenant-manuals", "document-1", "revision-1", 4, 1, 1))
                .thenReturn(List.of(new VectorStore.Document(
                        "chunk-4",
                        "单个上传文件最大为 20 MB。",
                        "upload-guide.md",
                        0.0,
                        Map.of(
                                "document_id", "document-1",
                                "revision_id", "revision-1"),
                        null,
                        4)));
        ContextBuilder contextBuilder = new ContextBuilder(vectorStore, new Reranker(null));

        List<com.harness.tool.rag.RagRetriever.RagDocument> documents =
                contextBuilder.readContext(
                        "tenant-manuals", "document-1", "revision-1", 4, 1, 1);

        assertThat(documents).singleElement().satisfies(document -> {
            assertThat(document.id()).isEqualTo("chunk-4");
            assertThat(document.chunkIndex()).isEqualTo(4);
        });
    }
}

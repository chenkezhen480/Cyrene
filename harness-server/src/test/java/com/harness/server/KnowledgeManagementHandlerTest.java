package com.harness.server;

import com.harness.core.model.PageInfo;
import com.harness.core.model.PageResponse;
import com.harness.server.api.ApiError;
import com.harness.server.api.ApiErrorCode;
import com.harness.provider.EmbeddingModelProvider;
import com.harness.tool.knowledge.FileStorageService;
import com.harness.tool.knowledge.KnowledgeChunkSummary;
import com.harness.tool.rag.VectorStore;
import dev.langchain4j.data.embedding.Embedding;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class KnowledgeManagementHandlerTest {

    @Test
    void listDocumentsUsesServerFilterAndReturnsExactPageContract() {
        VectorStore vectorStore = mock(VectorStore.class);
        Context context = mock(Context.class);
        PageResponse<KnowledgeChunkSummary> expected = new PageResponse<>(
                List.of(new KnowledgeChunkSummary(
                        "2", "upload-manual.md", 1, "doc-1", List.of("Upload"))),
                new PageInfo(20, "", false));

        when(context.pathParam("collection")).thenReturn("manuals");
        when(context.queryParam("fileName")).thenReturn(" upload ");
        when(context.queryParam("limit")).thenReturn("20");
        when(context.queryParam("cursor")).thenReturn("cursor-1");
        when(context.json(any())).thenReturn(context);
        when(vectorStore.listKnowledgeChunks(
                "manuals", "upload", 20, "cursor-1")).thenReturn(expected);

        handler(vectorStore).listDocuments(context);

        ArgumentCaptor<Object> response = ArgumentCaptor.forClass(Object.class);
        verify(context).json(response.capture());
        assertThat(response.getValue()).isSameAs(expected);
        verify(vectorStore).listKnowledgeChunks(
                "manuals", "upload", 20, "cursor-1");
    }

    @Test
    void listDocumentsMapsInvalidLimitToApiError() {
        VectorStore vectorStore = mock(VectorStore.class);
        Context context = mock(Context.class);
        when(context.pathParam("collection")).thenReturn("manuals");
        when(context.queryParam("fileName")).thenReturn(null);
        when(context.queryParam("limit")).thenReturn("101");
        when(context.status(400)).thenReturn(context);
        when(context.json(any())).thenReturn(context);

        handler(vectorStore).listDocuments(context);

        ArgumentCaptor<Object> response = ArgumentCaptor.forClass(Object.class);
        verify(context).json(response.capture());
        assertThat(response.getValue()).isInstanceOf(ApiError.class);
        assertThat(((ApiError) response.getValue()).code())
                .isEqualTo(ApiErrorCode.INVALID_REQUEST);
    }

    @Test
    void listDocumentsDoesNotTurnStorageFailureIntoEmptyPage() {
        VectorStore vectorStore = mock(VectorStore.class);
        Context context = mock(Context.class);
        when(context.pathParam("collection")).thenReturn("manuals");
        when(context.queryParam("fileName")).thenReturn(null);
        when(context.queryParam("limit")).thenReturn(null);
        when(context.queryParam("cursor")).thenReturn(null);
        when(context.status(500)).thenReturn(context);
        when(context.json(any())).thenReturn(context);
        when(vectorStore.listKnowledgeChunks("manuals", "", 50, null))
                .thenThrow(new IllegalStateException("database unavailable"));

        handler(vectorStore).listDocuments(context);

        ArgumentCaptor<Object> response = ArgumentCaptor.forClass(Object.class);
        verify(context).json(response.capture());
        assertThat(response.getValue()).isInstanceOf(ApiError.class);
        assertThat(((ApiError) response.getValue()).code())
                .isEqualTo(ApiErrorCode.INTERNAL_ERROR);
    }

    @Test
    void listCollectionsUsesSharedPageResponse() {
        VectorStore vectorStore = mock(VectorStore.class);
        Context context = mock(Context.class);
        PageResponse<String> expected = new PageResponse<>(
                List.of("manuals"), new PageInfo(20, "next", true));
        when(context.queryParam("limit")).thenReturn("20");
        when(context.queryParam("cursor")).thenReturn("cursor-1");
        when(context.json(any())).thenReturn(context);
        when(vectorStore.listCollections(20, "cursor-1")).thenReturn(expected);

        handler(vectorStore).listCollections(context);

        verify(vectorStore).listCollections(20, "cursor-1");
        verify(context).json(expected);
    }

    @Test
    void getDocumentScopesLookupToRouteCollection() {
        VectorStore vectorStore = mock(VectorStore.class);
        Context context = mock(Context.class);
        VectorStore.Document document = new VectorStore.Document(
                "chunk-1", "content", "manual.md", 1.0,
                java.util.Map.of(), null, 0);
        when(context.pathParam("collection")).thenReturn("manuals");
        when(context.pathParam("documentId")).thenReturn("chunk-1");
        when(context.json(any())).thenReturn(context);
        when(vectorStore.getById("manuals", "chunk-1")).thenReturn(document);

        handler(vectorStore).getDocument(context);

        verify(vectorStore).getById("manuals", "chunk-1");
        verify(context).json(document);
    }

    @Test
    void updateDocumentReembedsContentAndPreservesCollectionScope() {
        VectorStore vectorStore = mock(VectorStore.class);
        EmbeddingModelProvider embeddingProvider = mock(EmbeddingModelProvider.class);
        FileStorageService fileStorage = mock(FileStorageService.class);
        Context context = mock(Context.class);
        VectorStore.Document existing = new VectorStore.Document(
                "chunk-1", "old", "manual.md", 1.0,
                java.util.Map.of(), null, 0);
        float[] embedding = new float[]{0.1f, 0.2f};

        when(context.pathParam("collection")).thenReturn("manuals");
        when(context.pathParam("documentId")).thenReturn("chunk-1");
        when(context.bodyAsClass(java.util.Map.class))
                .thenReturn(java.util.Map.of("content", "new content"));
        when(context.json(any())).thenReturn(context);
        when(vectorStore.getById("manuals", "chunk-1")).thenReturn(existing);
        when(embeddingProvider.isAvailable()).thenReturn(true);
        when(embeddingProvider.embed("new content")).thenReturn(Embedding.from(embedding));

        new KnowledgeManagementHandler(vectorStore, embeddingProvider, fileStorage)
                .updateDocument(context);

        verify(vectorStore).updateContent("manuals", "chunk-1", "new content", embedding);
        verify(vectorStore, never()).upsert(any(), any());
    }

    @Test
    void deleteDocumentScopesDeletionToRouteCollection() {
        VectorStore vectorStore = mock(VectorStore.class);
        Context context = mock(Context.class);
        when(context.pathParam("collection")).thenReturn("manuals");
        when(context.pathParam("documentId")).thenReturn("chunk-1");
        when(context.json(any())).thenReturn(context);
        when(vectorStore.deleteById("manuals", "chunk-1")).thenReturn(true);

        handler(vectorStore).deleteDocument(context);

        verify(vectorStore).deleteById("manuals", "chunk-1");
    }

    private static KnowledgeManagementHandler handler(VectorStore vectorStore) {
        return new KnowledgeManagementHandler(
                vectorStore,
                mock(EmbeddingModelProvider.class),
                mock(FileStorageService.class));
    }
}

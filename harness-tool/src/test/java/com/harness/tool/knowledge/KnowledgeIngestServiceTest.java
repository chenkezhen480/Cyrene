package com.harness.tool.knowledge;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.text.UnicodeAwareTextTokenEstimator;
import com.harness.input.document.DocumentConversionDiagnostics;
import com.harness.input.document.DocumentConversionException;
import com.harness.input.document.DocumentConversionResult;
import com.harness.input.document.DocumentConversionService;
import com.harness.provider.EmbeddingModelProvider;
import com.harness.tool.rag.VectorStore;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeIngestServiceTest {

    @Mock
    private EmbeddingModelProvider embeddingProvider;
    @Mock
    private VectorStore vectorStore;
    @Mock
    private DocumentConversionService documentConversionService;
    @Mock
    private FileStorageService fileStorage;
    @Mock
    private Embedding embedding;

    private KnowledgeIngestService service;

    @BeforeEach
    void setUp() {
        EnvConfig.init(Map.of(
                EnvKey.KNOWLEDGE_MAX_FILE_SIZE_MB, "10",
                EnvKey.KNOWLEDGE_CHUNK_SIZE, "1024",
                EnvKey.RAG_COLLECTION, "default"
        ));
        when(embeddingProvider.tokenEstimator())
                .thenReturn(UnicodeAwareTextTokenEstimator.INSTANCE);
        service = new KnowledgeIngestService(
                embeddingProvider,
                vectorStore,
                documentConversionService,
                fileStorage);
    }

    @Test
    void ingestUsesCanonicalMarkdownAndPersistsConversionDiagnostics() {
        byte[] fileData = "source".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        DocumentConversionDiagnostics diagnostics = new DocumentConversionDiagnostics(
                "markitdown", "gpt-4o", true, List.of(),
                "chat", 2, 25, fileData.length);
        when(embeddingProvider.isAvailable()).thenReturn(true);
        when(embeddingProvider.dimension()).thenReturn(3);
        when(documentConversionService.convert(fileData, "report.pdf", "application/pdf"))
                .thenReturn(new DocumentConversionResult(
                        "# Report\n\nCanonical Markdown.",
                        "Report",
                        "application/pdf",
                        diagnostics));
        when(embeddingProvider.embedAll(anyList())).thenReturn(List.of(embedding));
        when(embedding.vector()).thenReturn(new float[]{1f, 2f, 3f});
        when(fileStorage.store(fileData, "report.pdf", "default"))
                .thenReturn("uploads/default/report.pdf");

        IngestResult result = service.ingest(
                fileData, "report.pdf", "application/pdf", null);

        ArgumentCaptor<List<VectorStore.Document>> documents = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).upsert(org.mockito.ArgumentMatchers.eq("default"), documents.capture());
        assertThat(documents.getValue()).singleElement().satisfies(document -> {
            assertThat(document.content()).isEqualTo("# Report\n\nCanonical Markdown.");
            assertThat(document.metadata())
                    .containsKeys("document_id")
                    .containsEntry("start_block_index", 0)
                    .containsEntry("end_block_index", 1)
                    .containsEntry("heading_path", List.of("Report"))
                    .containsEntry("token_estimator", "unicode-aware-estimate-v1")
                    .containsEntry("document_converter", "markitdown")
                    .containsEntry("document_vision_calls", 2)
                    .containsEntry("document_vision_model", "gpt-4o");
            assertThat(document.metadata().get("token_count")).isInstanceOf(Integer.class);
            assertThat(document.metadata().get("document_id").toString()).isNotBlank();
        });
        assertThat(result.documentConverter()).isEqualTo("markitdown");
        assertThat(result.detectedMimeType()).isEqualTo("application/pdf");
        assertThat(result.visionCalls()).isEqualTo(2);
    }

    @Test
    void writesStableDocumentAndOrderedChunkMetadataAcrossOneIngest() {
        EnvConfig.init(Map.of(
                EnvKey.KNOWLEDGE_MAX_FILE_SIZE_MB, "10",
                EnvKey.KNOWLEDGE_CHUNK_SIZE, "12",
                EnvKey.RAG_COLLECTION, "default"
        ));
        service = new KnowledgeIngestService(
                embeddingProvider,
                vectorStore,
                documentConversionService,
                fileStorage);
        byte[] fileData = "source".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        DocumentConversionDiagnostics diagnostics = new DocumentConversionDiagnostics(
                "markitdown", null, false, List.of(),
                "disabled", 0, 5, fileData.length);
        when(embeddingProvider.isAvailable()).thenReturn(true);
        when(documentConversionService.convert(fileData, "guide.md", "text/markdown"))
                .thenReturn(new DocumentConversionResult(
                        "第一段内容。\n\n第二段内容。\n\n第三段内容。",
                        "Guide",
                        "text/markdown",
                        diagnostics));
        when(embeddingProvider.embedAll(anyList()))
                .thenReturn(List.of(embedding, embedding));
        when(embedding.vector()).thenReturn(new float[]{1f});
        when(fileStorage.store(fileData, "guide.md", "default"))
                .thenReturn("uploads/default/guide.md");

        service.ingest(fileData, "guide.md", "text/markdown", null);

        ArgumentCaptor<List<VectorStore.Document>> documents = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).upsert(org.mockito.ArgumentMatchers.eq("default"), documents.capture());
        assertThat(documents.getValue()).hasSize(2);
        String documentId = documents.getValue().getFirst().metadata().get("document_id").toString();
        assertThat(documentId).isNotBlank();
        assertThat(documents.getValue()).allSatisfy(document ->
                assertThat(document.metadata()).containsEntry("document_id", documentId));
        assertThat(documents.getValue()).extracting(document ->
                        document.metadata().get("chunk_index"))
                .containsExactly(0, 1);
        assertThat(documents.getValue()).extracting(document ->
                        document.metadata().get("start_block_index"))
                .containsExactly(0, 2);
        assertThat(documents.getValue()).extracting(document ->
                        document.metadata().get("end_block_index"))
                .containsExactly(1, 2);
    }

    @Test
    void conversionFailureStopsBeforeEmbeddingAndStorage() {
        byte[] fileData = "source".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(embeddingProvider.isAvailable()).thenReturn(true);
        when(documentConversionService.convert(fileData, "broken.pdf", "application/pdf"))
                .thenThrow(new DocumentConversionException(
                        "conversion failed", 422, "DOCUMENT_CONVERSION_FAILED"));

        assertThatThrownBy(() -> service.ingest(
                fileData, "broken.pdf", "application/pdf", "default"))
                .isInstanceOf(DocumentConversionException.class)
                .hasMessageContaining("conversion failed");

        verify(embeddingProvider, never()).embedAll(anyList());
        verify(fileStorage, never()).store(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(vectorStore, never()).upsert(
                org.mockito.ArgumentMatchers.anyString(), anyList());
    }
}

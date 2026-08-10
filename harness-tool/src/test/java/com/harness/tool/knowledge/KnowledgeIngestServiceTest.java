package com.harness.tool.knowledge;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
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
                    .containsEntry("document_converter", "markitdown")
                    .containsEntry("document_vision_calls", 2)
                    .containsEntry("document_vision_model", "gpt-4o");
        });
        assertThat(result.documentConverter()).isEqualTo("markitdown");
        assertThat(result.detectedMimeType()).isEqualTo("application/pdf");
        assertThat(result.visionCalls()).isEqualTo(2);
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

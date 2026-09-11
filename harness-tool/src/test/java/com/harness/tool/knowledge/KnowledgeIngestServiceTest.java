package com.harness.tool.knowledge;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.knowledge.KnowledgeArtifact;
import com.harness.core.knowledge.KnowledgeIngestJob;
import com.harness.core.text.UnicodeAwareTextTokenEstimator;
import com.harness.input.document.DocumentConversionDiagnostics;
import com.harness.input.document.DocumentConversionException;
import com.harness.input.document.DocumentConversionResult;
import com.harness.input.document.DocumentConversionService;
import com.harness.provider.EmbeddingModelProvider;
import com.harness.tool.knowledge.authority.*;
import com.harness.tool.rag.VectorStore;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeIngestServiceTest {

    @TempDir
    Path tempDir;

    private EmbeddingModelProvider embeddingProvider;
    private VectorStore vectorStore;
    private DocumentConversionService conversionService;
    private KnowledgeArtifactRepository artifactRepository;
    private KnowledgeIngestJobStore ingestJobStore;
    private KnowledgeRepository knowledgeRepository;
    private AtomicReference<KnowledgeIngestJob> jobState;
    private Map<String, KnowledgeArtifact> artifacts;
    private Map<String, KnowledgeHead> heads;
    private KnowledgeIngestService service;

    @BeforeEach
    void setUp() {
        EnvConfig.init(Map.of(
                EnvKey.KNOWLEDGE_MAX_FILE_SIZE_MB, "10",
                EnvKey.KNOWLEDGE_CHUNK_SIZE, "1024",
                EnvKey.KNOWLEDGE_SOURCE_REVISION_MAX_CHUNKS, "100",
                EnvKey.KNOWLEDGE_INGEST_MAX_ATTEMPTS, "5",
                EnvKey.RAG_COLLECTION, "documents",
                EnvKey.KNOWLEDGE_CATALOG_COLLECTION, "catalog"));
        embeddingProvider = mock(EmbeddingModelProvider.class);
        vectorStore = mock(VectorStore.class);
        conversionService = mock(DocumentConversionService.class);
        artifactRepository = mock(KnowledgeArtifactRepository.class);
        ingestJobStore = mock(KnowledgeIngestJobStore.class);
        knowledgeRepository = mock(KnowledgeRepository.class);
        jobState = new AtomicReference<>();
        artifacts = new ConcurrentHashMap<>();
        heads = new ConcurrentHashMap<>();

        when(embeddingProvider.tokenEstimator()).thenReturn(UnicodeAwareTextTokenEstimator.INSTANCE);
        when(embeddingProvider.isAvailable()).thenReturn(true);
        when(embeddingProvider.dimension()).thenReturn(3);
        when(embeddingProvider.embedAll(anyList()))
                .thenAnswer(invocation -> invocation.<List<?>>getArgument(0).stream()
                        .map(ignored -> Embedding.from(new float[]{1, 2, 3}))
                        .toList());
        wirePersistentState();
        service = new KnowledgeIngestService(
                embeddingProvider,
                vectorStore,
                conversionService,
                new ContentAddressedArtifactStorage(tempDir),
                artifactRepository,
                ingestJobStore,
                knowledgeRepository);
    }

    @Test
    void ingestPersistsArtifactsRevisionAndStableWholeDocumentChunks() {
        byte[] bytes = "source".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(conversionService.convert(bytes, "report.pdf", "application/pdf"))
                .thenReturn(converted("# Report\n\nCanonical Markdown.", bytes.length));

        IngestResult result = service.ingest(
                bytes, "report.pdf", "application/pdf", "documents");

        assertThat(result.documentId()).isNotBlank();
        assertThat(result.revisionId()).isNotBlank();
        assertThat(result.sourceArtifactId()).isNotEqualTo(result.canonicalArtifactId());
        assertThat(jobState.get().status()).isEqualTo(KnowledgeIngestJob.Status.INDEXED);
        ArgumentCaptor<List<VectorStore.Document>> documents = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).upsert(eq("documents"), documents.capture());
        assertThat(documents.getValue()).singleElement().satisfies(chunk -> {
            assertThat(chunk.id()).hasSize(64);
            assertThat(chunk.source()).isEqualTo("report.pdf");
            assertThat(chunk.metadata())
                    .containsEntry("document_id", result.documentId())
                    .containsEntry("revision_id", result.revisionId())
                    .containsEntry("artifact_id", result.sourceArtifactId())
                    .containsEntry("canonical_artifact_id", result.canonicalArtifactId());
        });
        KnowledgeHead head = heads.get(result.documentId());
        assertThat(head.currentRevision().body()).isEqualTo("# Report\n\nCanonical Markdown.");
    }

    @Test
    void completeUpdateCreatesRevisionAndDeletesOnlyPreviousRevisionProjection() {
        byte[] first = "first".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(conversionService.convert(first, "guide.md", "text/markdown"))
                .thenReturn(converted("# Guide\n\nFirst.", first.length));
        IngestResult initial = service.ingest(
                first, "guide.md", "text/markdown", "documents");

        byte[] second = "second".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(conversionService.convert(second, "renamed-guide.md", "text/markdown"))
                .thenReturn(converted("# Guide v2\n\nSecond.", second.length));
        IngestResult updated = service.ingest(
                second, "renamed-guide.md", "text/markdown", "documents",
                initial.documentId(), null);

        assertThat(updated.documentId()).isEqualTo(initial.documentId());
        assertThat(updated.revisionId()).isNotEqualTo(initial.revisionId());
        assertThat(heads.get(initial.documentId()).currentRevision().revisionNumber()).isEqualTo(2);
        verify(vectorStore).deleteDocumentRevision(
                "documents", initial.documentId(), initial.revisionId());
        verify(vectorStore, never()).deleteById(anyString(), anyString());
    }

    @Test
    void transientFailureReturnsDurablePendingReceiptInsteadOfOpaqueFailure() {
        byte[] bytes = "source".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(conversionService.convert(bytes, "report.pdf", "application/pdf"))
                .thenThrow(new DocumentConversionException("parser unavailable"));

        KnowledgeIngestPendingException pending = catchThrowableOfType(
                () -> service.ingest(
                        bytes, "report.pdf", "application/pdf", "documents"),
                KnowledgeIngestPendingException.class);

        assertThat(pending.jobId()).isEqualTo(jobState.get().id());
        assertThat(pending.documentId()).isEqualTo(jobState.get().sourceConceptId());
        assertThat(pending.sourceArtifactId()).isEqualTo(jobState.get().artifactId());
        assertThat(pending.collection()).isEqualTo("documents");
        verify(ingestJobStore).reschedule(
                eq(jobState.get().id()), any(), eq("parser unavailable"));
        verify(ingestJobStore, never()).markFailed(anyString(), any(), anyString());
    }

    @Test
    void nonRetryableConversionFailureBecomesTerminalAndKeepsClientError() {
        byte[] bytes = "source".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(conversionService.convert(bytes, "report.bin", "application/octet-stream"))
                .thenThrow(new DocumentConversionException(
                        "unsupported document", 415, "unsupported_media_type"));

        assertThatThrownBy(() -> service.ingest(
                bytes, "report.bin", "application/octet-stream", "documents"))
                .isInstanceOf(DocumentConversionException.class)
                .hasMessage("unsupported document");

        verify(ingestJobStore).markFailed(
                eq(jobState.get().id()), any(), eq("unsupported document"));
        verify(ingestJobStore, never()).reschedule(anyString(), any(), anyString());
    }

    private void wirePersistentState() {
        when(artifactRepository.registerWithIngestJob(any(), any())).thenAnswer(invocation -> {
            KnowledgeArtifact artifact = invocation.getArgument(0);
            KnowledgeIngestJob job = invocation.getArgument(1);
            artifacts.putIfAbsent(artifact.id(), artifact);
            jobState.set(job);
            return new KnowledgeArtifactRepository.Registration(artifact, job);
        });
        when(artifactRepository.register(any())).thenAnswer(invocation -> {
            KnowledgeArtifact artifact = invocation.getArgument(0);
            artifacts.putIfAbsent(artifact.id(), artifact);
            return artifacts.get(artifact.id());
        });
        when(artifactRepository.findById(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(artifacts.get(invocation.getArgument(0))));
        when(knowledgeRepository.findById(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(heads.get(invocation.getArgument(0))));
        when(ingestJobStore.findById(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(jobState.get()));
        when(ingestJobStore.claim(anyString(), any())).thenAnswer(invocation -> {
            KnowledgeIngestJob current = jobState.get();
            KnowledgeIngestJob claimed = copyJob(
                    current, current.status(), current.attempts() + 1,
                    Instant.now(), current.convertedArtifactId(),
                    current.sourceRevisionId(), current.completedAt());
            jobState.set(claimed);
            return Optional.of(claimed);
        });
        when(ingestJobStore.advance(anyString(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    KnowledgeIngestJob current = jobState.get();
                    KnowledgeIngestJob.Status next = invocation.getArgument(2);
                    String converted = invocation.getArgument(3);
                    String concept = invocation.getArgument(4);
                    String revision = invocation.getArgument(5);
                    Instant completed = invocation.getArgument(6);
                    KnowledgeIngestJob advanced = new KnowledgeIngestJob(
                            current.id(), current.artifactId(), current.tenantId(),
                            current.collectionKey(), next, current.attempts(), current.availableAt(),
                            null, converted == null ? current.convertedArtifactId() : converted,
                            concept == null ? current.sourceConceptId() : concept,
                            revision == null ? current.sourceRevisionId() : revision,
                            null, current.createdAt(), completed);
                    jobState.set(advanced);
                    return advanced;
                });
        when(ingestJobStore.commitCompilation(anyString(), any())).thenAnswer(invocation -> {
            KnowledgeRevisionChange change = invocation.getArgument(1);
            heads.put(change.concept().id(), new KnowledgeHead(change.concept(), change.revision()));
            KnowledgeIngestJob current = jobState.get();
            KnowledgeIngestJob compiled = new KnowledgeIngestJob(
                    current.id(), current.artifactId(), current.tenantId(), current.collectionKey(),
                    KnowledgeIngestJob.Status.COMPILED, current.attempts(), current.availableAt(),
                    null, current.convertedArtifactId(), change.concept().id(),
                    change.revision().id(), null, current.createdAt(), null);
            jobState.set(compiled);
            return compiled;
        });
    }

    private static KnowledgeIngestJob copyJob(
            KnowledgeIngestJob job,
            KnowledgeIngestJob.Status status,
            int attempts,
            Instant claimedAt,
            String convertedArtifactId,
            String sourceRevisionId,
            Instant completedAt
    ) {
        return new KnowledgeIngestJob(
                job.id(), job.artifactId(), job.tenantId(), job.collectionKey(), status,
                attempts, job.availableAt(), claimedAt, convertedArtifactId,
                job.sourceConceptId(), sourceRevisionId, null, job.createdAt(), completedAt);
    }

    private static DocumentConversionResult converted(String markdown, long inputBytes) {
        return new DocumentConversionResult(
                markdown, null, "text/markdown",
                new DocumentConversionDiagnostics(
                        "markitdown", null, false, List.of(),
                        "disabled", 0, 5, inputBytes));
    }
}

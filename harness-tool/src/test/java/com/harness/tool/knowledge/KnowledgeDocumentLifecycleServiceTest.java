package com.harness.tool.knowledge;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.knowledge.*;
import com.harness.tool.knowledge.authority.KnowledgeHead;
import com.harness.tool.knowledge.authority.KnowledgeRepository;
import com.harness.tool.knowledge.authority.KnowledgeRevisionChange;
import com.harness.tool.rag.VectorStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class KnowledgeDocumentLifecycleServiceTest {

    @Test
    void deprecatesAuthorityBeforeDeletingCurrentRevisionChunks() {
        EnvConfig.init(Map.of(EnvKey.KNOWLEDGE_CATALOG_COLLECTION, "catalog"));
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        VectorStore vectorStore = mock(VectorStore.class);
        Instant now = Instant.parse("2026-09-02T00:00:00Z");
        KnowledgeRevision revision = new KnowledgeRevision(
                "revision-1", "document-1", 1, "Manual", null, "body",
                "compiler", now, KnowledgeIdentity.sha256("body"), Map.of(), now);
        KnowledgeConcept concept = new KnowledgeConcept(
                "document-1", null, null, KnowledgeNamespaceType.COLLECTION,
                "manuals", KnowledgeConceptType.SOURCE_DOCUMENT, null,
                KnowledgeStatus.STABLE, revision.id(), 1, null, now, now);
        when(repository.findAuthorityById("document-1"))
                .thenReturn(Optional.of(new KnowledgeHead(concept, revision)));
        when(vectorStore.deleteDocumentRevision("manuals", "document-1", "revision-1"))
                .thenReturn(4L);

        var result = new KnowledgeDocumentLifecycleService(repository, vectorStore)
                .deprecate("manuals", "document-1", null);

        assertThat(result.deletedChunkCount()).isEqualTo(4);
        ArgumentCaptor<List<KnowledgeRevisionChange>> changes =
                ArgumentCaptor.forClass(List.class);
        var inOrder = inOrder(repository, vectorStore);
        inOrder.verify(repository).commitChanges(changes.capture());
        inOrder.verify(vectorStore).deleteDocumentRevision(
                "manuals", "document-1", "revision-1");
        assertThat(changes.getValue()).singleElement().satisfies(change -> {
            assertThat(change.concept().status()).isEqualTo(KnowledgeStatus.DEPRECATED);
            assertThat(change.indexTasks()).singleElement().satisfies(task -> {
                assertThat(task.operation()).isEqualTo(KnowledgeIndexOperation.DELETE_CONCEPT);
            });
        });
    }

    @Test
    void deprecatedRetryStillDeletesThePreviouslyIndexedRevision() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        VectorStore vectorStore = mock(VectorStore.class);
        Instant now = Instant.parse("2026-09-02T00:00:00Z");
        KnowledgeRevision deprecatedRevision = new KnowledgeRevision(
                "revision-2", "document-1", 2, "Manual", null, "body",
                "cyrene-document-lifecycle/v1", now, KnowledgeIdentity.sha256("deprecated"),
                Map.of("previousRevisionId", "revision-1"), now);
        KnowledgeConcept deprecatedConcept = new KnowledgeConcept(
                "document-1", null, null, KnowledgeNamespaceType.COLLECTION,
                "manuals", KnowledgeConceptType.SOURCE_DOCUMENT, null,
                KnowledgeStatus.DEPRECATED, deprecatedRevision.id(), 2, null, now, now);
        when(repository.findAuthorityById("document-1"))
                .thenReturn(Optional.of(new KnowledgeHead(
                        deprecatedConcept, deprecatedRevision)));
        when(vectorStore.deleteDocumentRevision(
                "manuals", "document-1", "revision-1")).thenReturn(4L);

        var result = new KnowledgeDocumentLifecycleService(repository, vectorStore)
                .deprecate("manuals", "document-1", null);

        assertThat(result.deletedChunkCount()).isEqualTo(4);
        verify(repository, never()).commitChanges(any());
        verify(vectorStore).deleteDocumentRevision(
                "manuals", "document-1", "revision-1");
    }
}

package com.harness.tool.knowledge;

import com.harness.core.knowledge.*;
import com.harness.core.model.*;
import com.harness.tool.knowledge.authority.*;
import com.harness.tool.rag.VectorStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeWikiServiceTest {
    private final KnowledgeRepository repository = mock(KnowledgeRepository.class);
    private final VectorStore vectors = mock(VectorStore.class);
    private final KnowledgeWikiService service = new KnowledgeWikiService(repository, vectors);
    private final Instant now = Instant.parse("2026-09-14T00:00:00Z");

    private KnowledgeHead setup(KnowledgeConceptType type) {
        var namespace = type == KnowledgeConceptType.USER_EPISODE ? KnowledgeNamespaceType.USER_MEMORY : KnowledgeNamespaceType.COLLECTION;
        var revision = new KnowledgeRevision("rev-1", "doc-1", 1, "Original title", "Discovery summary", "original source facts",
                "compiler", now, KnowledgeIdentity.sha256("original source facts"), Map.of(), now);
        var concept = new KnowledgeConcept("doc-1", null, type == KnowledgeConceptType.USER_EPISODE ? "alice" : null,
                namespace, "manuals", type, null, KnowledgeStatus.STABLE, revision.id(), 1, null, now, now);
        var head = new KnowledgeHead(concept, revision);
        var snapshot = new KnowledgeRevisionSnapshot(type, "manuals", revision,
                List.of(new KnowledgeSource(revision.id(), KnowledgeSourceType.KNOWLEDGE_ARTIFACT, "artifact-1", "cyrene://artifacts/artifact-1", now, now)), List.of());
        when(repository.findAuthorityById("doc-1")).thenReturn(Optional.of(head));
        when(repository.findMetadataSnapshot("rev-1")).thenReturn(snapshot);
        when(repository.findSnapshot("rev-1")).thenReturn(snapshot);
        doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; }).when(repository).withAuthorityLock(anyString(), any());
        return head;
    }

    @Test
    void listUsesAuthorityPaginationAndOmitsSourceBodies() {
        var head = setup(KnowledgeConceptType.SOURCE_DOCUMENT);
        var info = new PageInfo(1, "2026-09-14T00:00:00Z|doc-1", true);
        when(repository.findPageInNamespace(null, KnowledgeNamespaceType.COLLECTION, "manuals", KnowledgeConceptType.SOURCE_DOCUMENT,
                KnowledgeStatus.STABLE, null, 1)).thenReturn(new PageResponse<>(List.of(head.concept()), info));
        var page = service.page(null, "alice", KnowledgeConceptType.SOURCE_DOCUMENT, "manuals", null, 1, ignored -> true);
        assertThat(page.pageInfo()).isEqualTo(info);
        assertThat(page.items()).singleElement().satisfies(card -> {
            assertThat(card.title()).isEqualTo("Original title");
            assertThat(card.capability()).isEmpty();
            assertThat(service.markdown(card)).doesNotContain("original source facts").contains("Discovery summary");
        });
        verify(repository, never()).findSnapshot(anyString());
    }

    @Test
    void documentEditPreservesChunksSourcesAndSchedulesTheNewCard() {
        setup(KnowledgeConceptType.SOURCE_DOCUMENT);
        var result = service.update("doc-1", "rev-1", "Edited title", "**Edited** summary", "alice", ignored -> true);
        ArgumentCaptor<List<KnowledgeRevisionChange>> changes = ArgumentCaptor.forClass(List.class);
        var ordered = inOrder(vectors, repository);
        ordered.verify(vectors).copyDocumentRevision("manuals", "doc-1", "rev-1", result.revisionId());
        ordered.verify(repository).commitChanges(changes.capture());
        assertThat(result.version()).isEqualTo(2);
        assertThat(changes.getValue()).singleElement().satisfies(change -> {
            assertThat(change.revision().body()).isEqualTo("original source facts");
            assertThat(change.revision().title()).isEqualTo("Edited title");
            assertThat(change.revision().metadata()).containsEntry("previousRevisionId", "rev-1");
            assertThat(change.sources()).singleElement().satisfies(source -> {
                assertThat(source.revisionId()).isEqualTo(result.revisionId());
                assertThat(source.sourceId()).isEqualTo("artifact-1");
            });
            assertThat(change.indexTasks()).singleElement().satisfies(task -> {
                assertThat(task.revisionId()).isEqualTo(result.revisionId());
                assertThat(task.operation()).isEqualTo(KnowledgeIndexOperation.UPSERT_CURRENT);
            });
        });
        verify(vectors, never()).deleteDocumentRevision("manuals", "doc-1", "rev-1");
    }

    @Test
    void identicalEditKeepsTheCurrentRevision() {
        setup(KnowledgeConceptType.SOURCE_DOCUMENT);

        var result = service.update("doc-1", "rev-1", "Original title",
                "Discovery summary", "alice", ignored -> true);

        assertThat(result.revisionId()).isEqualTo("rev-1");
        assertThat(result.version()).isEqualTo(1);
        verify(repository, never()).commitChanges(any());
        verifyNoInteractions(vectors);
    }

    @Test
    void failedCommitCleansOnlyTheCopiedNewRevision() {
        setup(KnowledgeConceptType.SOURCE_DOCUMENT);
        doThrow(new IllegalStateException("commit failed")).when(repository).commitChanges(any());
        assertThatThrownBy(() -> service.update("doc-1", "rev-1", "Edited", "summary", "alice", ignored -> true))
                .isInstanceOf(IllegalStateException.class).hasMessage("commit failed");
        ArgumentCaptor<String> revision = ArgumentCaptor.forClass(String.class);
        verify(vectors).deleteDocumentRevision(eq("manuals"), eq("doc-1"), revision.capture());
        assertThat(revision.getValue()).isNotEqualTo("rev-1");
    }

    @Test
    void staleUnauthorizedAndPreferenceEditsDoNotWrite() {
        setup(KnowledgeConceptType.SOURCE_DOCUMENT);
        assertThatThrownBy(() -> service.update("doc-1", "stale", "Edited", "summary", "alice", ignored -> true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.update("doc-1", "rev-1", "Edited", "summary", "alice", ignored -> false)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.page(null, "alice", KnowledgeConceptType.USER_PREFERENCE, null, null, 20, ignored -> true)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(vectors);
        verify(repository, never()).commitChanges(any());
    }

    @Test
    void episodeEditPreservesUserOwnershipAndItsOriginalMemoryBody() {
        setup(KnowledgeConceptType.USER_EPISODE);
        var card = service.update("doc-1", "rev-1", "New discovery title", "New summary", "alice", ignored -> true);
        ArgumentCaptor<List<KnowledgeRevisionChange>> changes = ArgumentCaptor.forClass(List.class);
        verify(repository).commitChanges(changes.capture());
        assertThat(changes.getValue().getFirst().concept().userId()).isEqualTo("alice");
        assertThat(changes.getValue().getFirst().revision().body()).isEqualTo("original source facts");
        assertThat(card.capability()).isEmpty();
        verifyNoInteractions(vectors);
    }

    @Test
    void deleteDeprecatesTheAuthorityAndSchedulesProjectionRemoval() {
        setup(KnowledgeConceptType.USER_EPISODE);

        var result = service.delete("doc-1", "rev-1", ignored -> true);

        ArgumentCaptor<List<KnowledgeRevisionChange>> changes = ArgumentCaptor.forClass(List.class);
        verify(repository).commitChanges(changes.capture());
        assertThat(result.deleted()).isTrue();
        assertThat(changes.getValue()).singleElement().satisfies(change -> {
            assertThat(change.concept().status()).isEqualTo(KnowledgeStatus.DEPRECATED);
            assertThat(change.concept().userId()).isEqualTo("alice");
            assertThat(change.revision().body()).isEqualTo("original source facts");
            assertThat(change.revision().metadata()).containsEntry("previousRevisionId", "rev-1");
            assertThat(change.indexTasks()).singleElement().satisfies(task ->
                    assertThat(task.operation()).isEqualTo(KnowledgeIndexOperation.DELETE_CONCEPT));
        });
    }
    /**
     * A graph card mirrors its Schema or Graph Space. Deleting it alone would be undone by the next
     * Schema save and would leave a live space pointing at a discontinued card, so it is refused.
     */
    @Test
    void deleteRefusesGraphSchemaAndGraphSpaceCards() {
        for (var type : List.of(KnowledgeConceptType.GRAPH_SCHEMA, KnowledgeConceptType.GRAPH_SPACE)) {
            setup(type);

            assertThatThrownBy(() -> service.delete("doc-1", "rev-1", ignored -> true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("follows its entity lifecycle");
            verify(repository, never()).commitChanges(any());
        }
    }

    @Test
    void globalManagementExportIncludesAllOwnersAcrossCollectionsAndPagesWithoutDuplicatingGraphDescriptions() {
        var cursor = new KnowledgeConceptCursor(now, "hidden");
        var hidden = exportHead("hidden", KnowledgeConceptType.SOURCE_DOCUMENT, "secret", null);
        var first = exportHead("doc-a", KnowledgeConceptType.SOURCE_DOCUMENT, "collection-a", null);
        var second = exportHead("doc-b", KnowledgeConceptType.SOURCE_DOCUMENT, "collection-b", null);
        when(repository.findManagementPage(KnowledgeConceptType.SOURCE_DOCUMENT,
                KnowledgeStatus.STABLE, null, 100)).thenReturn(new PageResponse<>(List.of(hidden.concept()), new PageInfo(100, now + "|hidden", true)));
        when(repository.findManagementPage(KnowledgeConceptType.SOURCE_DOCUMENT,
                KnowledgeStatus.STABLE, cursor, 100)).thenReturn(new PageResponse<>(List.of(first.concept(), second.concept()), new PageInfo(100, "", false)));
        for (var type : List.of(KnowledgeConceptType.GRAPH_SCHEMA, KnowledgeConceptType.GRAPH_SPACE,
                KnowledgeConceptType.USER_EPISODE, KnowledgeConceptType.OPERATION_PLAYBOOK)) {
            var head = exportHead(type.name(), type, "schema-a", type == KnowledgeConceptType.USER_EPISODE ? "another-user" : null);
            when(repository.findManagementPage(type, KnowledgeStatus.STABLE, null, 100)).thenReturn(new PageResponse<>(List.of(head.concept()), new PageInfo(100, "", false)));
        }
        String result = service.exportMarkdown();
        assertThat(result).contains("collection-a", "collection-b", "summary-doc-a", "summary-doc-b",
                "summary-USER_EPISODE", "summary-OPERATION_PLAYBOOK", "summary-GRAPH_SCHEMA", "graph-a", "schema-a", "summary-hidden")
                .doesNotContain("summary-GRAPH_SPACE", "source-facts", "USER_PREFERENCE");
        verify(repository, never()).findPageInNamespace(any(), any(), any(), any(), any(), any(), anyInt());
        verify(repository, never()).findSnapshot(anyString());
        verify(repository, never()).findPage(any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void globalExportRejectsAPageWhoseCursorDoesNotAdvance() {
        when(repository.findManagementPage(eq(KnowledgeConceptType.SOURCE_DOCUMENT), eq(KnowledgeStatus.STABLE), nullable(KnowledgeConceptCursor.class), eq(100))).thenReturn(new PageResponse<>(List.of(), new PageInfo(100, now + "|stuck", true)));
        assertThatThrownBy(() -> service.exportMarkdown())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("did not advance");
    }

    private KnowledgeHead exportHead(String id, KnowledgeConceptType type, String collection, String userId) {
        var namespace = type == KnowledgeConceptType.USER_EPISODE ? KnowledgeNamespaceType.USER_MEMORY
                : type == KnowledgeConceptType.OPERATION_PLAYBOOK ? KnowledgeNamespaceType.OPERATION_MEMORY
                : type == KnowledgeConceptType.SOURCE_DOCUMENT ? KnowledgeNamespaceType.COLLECTION : KnowledgeNamespaceType.GRAPH;
        var revision = new KnowledgeRevision("revision-" + id, id, 1, "title-" + id, "summary-" + id,
                type == KnowledgeConceptType.GRAPH_SCHEMA ? "Schema capability" : "source-facts",
                "compiler", now, id, Map.of("graphId", "graph-a", "schemaId", "schema-a"), now);
        var concept = new KnowledgeConcept(id, null, userId, namespace, collection, type, id,
                KnowledgeStatus.STABLE, revision.id(), 1, null, now, now);
        var head = new KnowledgeHead(concept, revision);
        when(repository.findAuthorityById(id)).thenReturn(Optional.of(head));
        when(repository.findMetadataSnapshot(revision.id())).thenReturn(new KnowledgeRevisionSnapshot(type, collection, revision, List.of(), List.of()));
        return head;
    }

}

package com.harness.tool.knowledge.okf;

import com.harness.core.knowledge.KnowledgeConcept;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeNamespaceType;
import com.harness.core.knowledge.KnowledgeRevision;
import com.harness.core.knowledge.KnowledgeSource;
import com.harness.core.knowledge.KnowledgeSourceType;
import com.harness.core.knowledge.KnowledgeStatus;
import com.harness.core.knowledge.KnowledgeVerification;
import com.harness.core.knowledge.KnowledgeVerificationResult;
import com.harness.core.knowledge.KnowledgeVerificationType;
import com.harness.core.knowledge.OkfKnowledgeDocument;
import com.harness.core.model.PageInfo;
import com.harness.core.model.PageResponse;
import com.harness.tool.knowledge.authority.KnowledgeHead;
import com.harness.tool.knowledge.authority.KnowledgeIndexOutboxStore;
import com.harness.tool.knowledge.authority.KnowledgeRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OkfBundleExporterTest {

    private static final Instant NOW = Instant.parse("2026-09-03T03:00:00Z");

    @Test
    void export_isolatesOwnerReauthorizesSourcesAndProducesParseableBundle() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KnowledgeIndexOutboxStore outboxStore = mock(KnowledgeIndexOutboxStore.class);
        KnowledgeConcept concept = concept("concept-user-1", "user-1");
        KnowledgeRevision revision = revision(concept, "Authorization: Bearer private-value");
        KnowledgeHead head = new KnowledgeHead(concept, revision);
        KnowledgeSource allowed = new KnowledgeSource(
                revision.id(), KnowledgeSourceType.SESSION_MESSAGE, "message-1",
                "cyrene://session/s1/message/1", NOW.minusSeconds(60), NOW);
        KnowledgeSource denied = new KnowledgeSource(
                revision.id(), KnowledgeSourceType.SESSION_MESSAGE, "message-2",
                "cyrene://session/other/message/2", NOW.minusSeconds(30), NOW);
        KnowledgeVerification verification = new KnowledgeVerification(
                1L, revision.id(), "human:user-1", KnowledgeVerificationType.HUMAN_REVIEWED,
                KnowledgeVerificationResult.PASSED, null, NOW);

        when(repository.findPage(
                null, "user-1", KnowledgeNamespaceType.USER_MEMORY,
                KnowledgeConceptType.USER_PREFERENCE, null, null, 100))
                .thenReturn(page(List.of(concept)));
        when(repository.findPage(
                null, "user-1", KnowledgeNamespaceType.USER_MEMORY,
                KnowledgeConceptType.USER_EPISODE, null, null, 100))
                .thenReturn(page(List.of()));
        when(repository.findByIds(List.of(concept.id())))
                .thenReturn(Map.of(concept.id(), head));
        when(repository.findByIds(List.of())).thenReturn(Map.of());
        when(repository.findSourcePage(eq(revision.id()), any(), eq(100)))
                .thenReturn(page(List.of(allowed, denied)));
        when(repository.findVerificationPage(eq(revision.id()), any(), eq(100)))
                .thenReturn(page(List.of(verification)));
        when(repository.findOutgoingLinkPage(eq(concept.id()), any(), eq(100)))
                .thenReturn(page(List.of()));
        when(repository.findRevisionPage(concept.id(), null, 100))
                .thenReturn(page(List.of(revision)));
        when(outboxStore.findPageByConceptIds(anyList(), eq(0L), eq(100)))
                .thenReturn(page(List.of()));

        OkfBundleExporter exporter = new OkfBundleExporter(
                repository,
                outboxStore,
                (scope, candidateConcept, source) -> source.sourceId().equals("message-1"),
                Clock.fixed(NOW, ZoneOffset.UTC));
        OkfBundle bundle = exporter.export(OkfBundleScope.user(null, "user-1"));

        assertThat(bundle.conceptCount()).isEqualTo(1);
        assertThat(bundle.omittedSourceCount()).isEqualTo(1);
        assertThat(bundle.files()).containsKeys("index.md", "log.md");
        assertThat(bundle.files().values()).allMatch(value -> !value.contains("private-value"));
        String conceptMarkdown = bundle.files().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("concepts/"))
                .findFirst().orElseThrow().getValue();
        assertThat(conceptMarkdown)
                .contains("cyrene://session/s1/message/1")
                .doesNotContain("cyrene://session/other/message/2", "private-value")
                .contains("[REDACTED]");
        assertThat(new OkfMarkdownCodec().read(conceptMarkdown).sources())
                .extracting(OkfKnowledgeDocument.Source::id)
                .containsExactly("message-1");
        assertThat(bundle.files().get("index.md"))
                .contains("okf_version: \"0.2\"", "User Preference", "stable");
        assertThat(bundle.files().get("log.md"))
                .contains("revision", "verification", "human:user-1");
        verify(repository).findPage(
                null, "user-1", KnowledgeNamespaceType.USER_MEMORY,
                KnowledgeConceptType.USER_PREFERENCE, null, null, 100);
    }

    @Test
    void scope_neverAllowsAnotherUserTenantOrOperationClass() {
        OkfBundleScope userScope = OkfBundleScope.user("tenant-a", "user-1");

        assertThat(userScope.permits(concept("one", "user-1", "tenant-a"))).isTrue();
        assertThat(userScope.permits(concept("two", "user-2", "tenant-a"))).isFalse();
        assertThat(userScope.permits(concept("three", "user-1", "tenant-b"))).isFalse();
        assertThat(userScope.permits(operation("four", "tenant-a"))).isFalse();
        assertThat(OkfBundleScope.tenantOperation("tenant-a")
                .permits(operation("four", "tenant-a"))).isTrue();
        assertThat(OkfBundleScope.globalOperation()
                .permits(operation("five", null))).isTrue();
        assertThat(OkfBundleScope.globalOperation()
                .permits(operation("four", "tenant-a"))).isFalse();
    }

    @Test
    void exportGraphIncludesAvailableGraphWikiTypes() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KnowledgeIndexOutboxStore outboxStore = mock(KnowledgeIndexOutboxStore.class);
        KnowledgeConcept schemaConcept = new KnowledgeConcept(
                "graph-schema-1", null, null, KnowledgeNamespaceType.GRAPH,
                "schema-1", KnowledgeConceptType.GRAPH_SCHEMA, "schema-1",
                KnowledgeStatus.STABLE, "graph-schema-revision-1", 1, null, NOW, NOW);
        KnowledgeConcept spaceConcept = new KnowledgeConcept(
                "graph-space-1", null, null, KnowledgeNamespaceType.GRAPH,
                "graph-1:schema-1", KnowledgeConceptType.GRAPH_SPACE, "graph-1",
                KnowledgeStatus.STABLE, "graph-space-revision-1", 1, null, NOW, NOW);
        KnowledgeRevision schemaRevision = revision(
                schemaConcept, "Graph schema description");
        KnowledgeRevision spaceRevision = revision(
                spaceConcept, "Graph space description");

        when(repository.findPageInNamespace(
                null, KnowledgeNamespaceType.GRAPH, "schema-1",
                KnowledgeConceptType.GRAPH_SCHEMA, null, null, 100))
                .thenReturn(page(List.of(schemaConcept)));
        when(repository.findPageInNamespace(
                null, KnowledgeNamespaceType.GRAPH, "graph-1:schema-1",
                KnowledgeConceptType.GRAPH_SPACE, null, null, 100))
                .thenReturn(page(List.of(spaceConcept)));
        when(repository.findByIds(List.of(schemaConcept.id())))
                .thenReturn(Map.of(schemaConcept.id(),
                        new KnowledgeHead(schemaConcept, schemaRevision)));
        when(repository.findByIds(List.of(spaceConcept.id())))
                .thenReturn(Map.of(spaceConcept.id(),
                        new KnowledgeHead(spaceConcept, spaceRevision)));
        when(repository.findByIds(List.of())).thenReturn(Map.of());
        when(repository.findSourcePage(any(), any(), eq(100)))
                .thenReturn(page(List.of()));
        when(repository.findVerificationPage(any(), any(), eq(100)))
                .thenReturn(page(List.of()));
        when(repository.findOutgoingLinkPage(any(), any(), eq(100)))
                .thenReturn(page(List.of()));
        when(repository.findRevisionPage(schemaConcept.id(), null, 100))
                .thenReturn(page(List.of(schemaRevision)));
        when(repository.findRevisionPage(spaceConcept.id(), null, 100))
                .thenReturn(page(List.of(spaceRevision)));
        when(outboxStore.findPageByConceptIds(anyList(), eq(0L), eq(100)))
                .thenReturn(page(List.of()));

        OkfBundle bundle = new OkfBundleExporter(
                repository, outboxStore,
                (scope, candidateConcept, source) -> true,
                Clock.fixed(NOW, ZoneOffset.UTC))
                .export(OkfBundleScope.graph(
                        "tenant-a", "graph-1", "schema-1"));

        List<OkfKnowledgeDocument> documents = bundle.files().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("concepts/"))
                .map(entry -> new OkfMarkdownCodec().read(entry.getValue()))
                .toList();
        assertThat(documents).extracting(OkfKnowledgeDocument::type)
                .containsExactlyInAnyOrder(
                        KnowledgeConceptType.GRAPH_SCHEMA.displayName(),
                        KnowledgeConceptType.GRAPH_SPACE.displayName());
        assertThat(documents).allSatisfy(document -> assertThat(document.extensions())
                .doesNotContainKey("x-cyrene-graph-bindings"));
        verify(repository).findPageInNamespace(
                null, KnowledgeNamespaceType.GRAPH, "schema-1",
                KnowledgeConceptType.GRAPH_SCHEMA, null, null, 100);
        verify(repository).findPageInNamespace(
                null, KnowledgeNamespaceType.GRAPH, "graph-1:schema-1",
                KnowledgeConceptType.GRAPH_SPACE, null, null, 100);
    }

    private static KnowledgeConcept concept(String id, String userId) {
        return concept(id, userId, null);
    }

    private static KnowledgeConcept concept(String id, String userId, String tenantId) {
        return new KnowledgeConcept(
                id, tenantId, userId, KnowledgeNamespaceType.USER_MEMORY, null,
                KnowledgeConceptType.USER_PREFERENCE, "response.verbosity",
                KnowledgeStatus.STABLE, id + "-revision", 1, null, NOW, NOW);
    }

    private static KnowledgeConcept operation(String id, String tenantId) {
        return new KnowledgeConcept(
                id, tenantId, null, KnowledgeNamespaceType.OPERATION_MEMORY, null,
                KnowledgeConceptType.OPERATION_PLAYBOOK, "task:search",
                KnowledgeStatus.STABLE, id + "-revision", 1, null, NOW, NOW);
    }

    private static KnowledgeRevision revision(KnowledgeConcept concept, String body) {
        return new KnowledgeRevision(
                concept.currentRevisionId(), concept.id(), 1, "Response verbosity",
                "Current response preference", body, "compiler/v1", NOW,
                "content-hash", Map.of(), NOW);
    }

    private static <T> PageResponse<T> page(List<T> items) {
        return new PageResponse<>(items, new PageInfo(100, "", false));
    }
}

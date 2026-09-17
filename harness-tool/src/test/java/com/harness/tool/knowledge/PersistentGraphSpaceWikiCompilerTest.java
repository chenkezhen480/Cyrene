package com.harness.tool.knowledge;

import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeIndexOperation;
import com.harness.core.knowledge.KnowledgeNamespaceType;
import com.harness.core.knowledge.KnowledgeStatus;
import com.harness.graph.model.GraphChangeSet;
import com.harness.graph.model.GraphMutationResult;
import com.harness.graph.model.GraphNode;
import com.harness.graph.model.GraphRelation;
import com.harness.tool.knowledge.authority.KnowledgeHead;
import com.harness.tool.knowledge.authority.KnowledgeRepository;
import com.harness.tool.knowledge.authority.KnowledgeRevisionChange;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersistentGraphSpaceWikiCompilerTest {

    @Test
    void createsOneCatalogEntryThatPointsToTheConcreteGraphSpace() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        when(repository.findById(any())).thenReturn(Optional.empty());
        WikiIdentityResolver identityResolver = mock(WikiIdentityResolver.class);
        when(identityResolver.resolve(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        PersistentGraphSpaceWikiCompiler compiler =
                new PersistentGraphSpaceWikiCompiler(
                        repository, schemaRegistry(), identityResolver);
        GraphChangeSet changeSet = changeSet();

        compiler.synchronize(changeSet,
                new GraphMutationResult("request-1", true, 2, 1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeRevisionChange>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(repository).commitChanges(captor.capture());
        KnowledgeRevisionChange change = captor.getValue().getFirst();
        assertThat(change.concept().conceptType())
                .isEqualTo(KnowledgeConceptType.GRAPH_SPACE);
        assertThat(change.concept().namespaceType())
                .isEqualTo(KnowledgeNamespaceType.GRAPH);
        assertThat(change.concept().namespaceKey()).isEqualTo("graph-a:schema-a");
        assertThat(change.concept().logicalKey()).isEqualTo("graph-a");
        assertThat(change.concept().status()).isEqualTo(KnowledgeStatus.STABLE);
        assertThat(change.revision().body())
                .contains("Graph Space graph-a", "Schema: schema-a", "Nodes:",
                        "Student -[KNOWS]-> Student", "Supported queries",
                        // Student is both a source and a target of KNOWS, so a two-hop path exists.
                        "bounded paths", "query_graph")
                .doesNotContain("Alice", "Bob", "AI capability description", "- Version:", "Typical queries");
        // The space card carries a generated one-liner, never a model-written description: describing
        // what a graph can answer is the Schema card's job.
        assertThat(change.revision().description()).isEqualTo("Graph Space graph-a on Schema schema-a.");
        assertThat(change.revision().body()).contains(change.revision().description());
        assertThat(change.revision().metadata())
                .containsEntry("recommendedTool", "query_graph")
                .containsEntry("entityTypes", List.of("Class", "Student"))
                .containsEntry("graphId", "graph-a")
                .containsEntry("schemaId", "schema-a")
                .containsEntry("resourceUri", "cyrene://graphs/graph-a?schemaId=schema-a");
        assertThat(change.indexTasks()).singleElement()
                .satisfies(task -> assertThat(task.operation())
                        .isEqualTo(KnowledgeIndexOperation.UPSERT_CURRENT));
        verify(identityResolver).resolve(
                org.mockito.ArgumentMatchers.eq(KnowledgeConceptType.GRAPH_SPACE),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(KnowledgeNamespaceType.GRAPH),
                org.mockito.ArgumentMatchers.eq("graph-a:schema-a"),
                org.mockito.ArgumentMatchers.eq("graph-a"), any(),
                org.mockito.ArgumentMatchers.eq(
                        WikiIdentityResolver.RevisionMode.AUTHORITATIVE_SNAPSHOT));
    }

    @Test
    void existingStableGraphSpaceDoesNotCreateRevisionPerMutation() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        PersistentGraphSpaceWikiCompiler compiler =
                new PersistentGraphSpaceWikiCompiler(repository, schemaRegistry());
        when(repository.findById(any())).thenReturn(Optional.empty());
        compiler.synchronize(changeSet(),
                new GraphMutationResult("request-1", true, 2, 1));
        KnowledgeRevisionChange first = capture(repository);
        org.mockito.Mockito.clearInvocations(repository);
        when(repository.findById(any())).thenReturn(Optional.of(
                new KnowledgeHead(first.concept(), first.revision())));

        compiler.synchronize(changeSet(),
                new GraphMutationResult("request-1", true, 2, 1));

        verify(repository, never()).commitChanges(any());
    }

    /**
     * The card is looked up by the identity {@code synchronize} wrote. A different namespaceKey or
     * logicalKey here would create a second orphan concept instead of discontinuing this one.
     */
    @Test
    void deprecateUsesTheSameConceptIdentityAsSynchronize() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        when(repository.findById(any())).thenReturn(Optional.empty());
        PersistentGraphSpaceWikiCompiler compiler =
                new PersistentGraphSpaceWikiCompiler(repository, schemaRegistry());
        compiler.synchronize(changeSet(), new GraphMutationResult("request-1", true, 2, 1));
        KnowledgeRevisionChange created = capture(repository);

        reset(repository);
        when(repository.findById(created.concept().id()))
                .thenReturn(Optional.of(new KnowledgeHead(created.concept(), created.revision())));

        compiler.deprecate("graph-a", "schema-a");

        verify(repository).findById(created.concept().id());
        KnowledgeRevisionChange deprecated = capture(repository);
        assertThat(deprecated.concept().id()).isEqualTo(created.concept().id());
        assertThat(deprecated.concept().status()).isEqualTo(KnowledgeStatus.DEPRECATED);
        assertThat(deprecated.concept().namespaceKey()).isEqualTo("graph-a:schema-a");
        assertThat(deprecated.concept().logicalKey()).isEqualTo("graph-a");
        assertThat(deprecated.revision().body()).contains("Status: deprecated");
        assertThat(deprecated.indexTasks()).singleElement()
                .satisfies(task -> assertThat(task.operation())
                        .isEqualTo(KnowledgeIndexOperation.DELETE_CONCEPT));
    }

    @Test
    void deprecateIsIdempotentForAnAlreadyDiscontinuedSpace() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        when(repository.findById(any())).thenReturn(Optional.empty());
        PersistentGraphSpaceWikiCompiler compiler =
                new PersistentGraphSpaceWikiCompiler(repository, schemaRegistry());
        compiler.synchronize(changeSet(), new GraphMutationResult("request-1", true, 2, 1));
        KnowledgeRevisionChange created = capture(repository);
        var stable = created.concept();
        var discontinued = new com.harness.core.knowledge.KnowledgeConcept(
                stable.id(), stable.tenantId(), stable.userId(), stable.namespaceType(),
                stable.namespaceKey(), stable.conceptType(), stable.logicalKey(),
                KnowledgeStatus.DEPRECATED, stable.currentRevisionId(), 2, null,
                stable.createdAt(), stable.updatedAt());

        reset(repository);
        when(repository.findById(any()))
                .thenReturn(Optional.of(new KnowledgeHead(discontinued, created.revision())));

        compiler.deprecate("graph-a", "schema-a");

        verify(repository, never()).commitChanges(any());
    }

    private static KnowledgeRevisionChange capture(KnowledgeRepository repository) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeRevisionChange>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(repository).commitChanges(captor.capture());
        return captor.getValue().getFirst();
    }

    private static com.harness.graph.schema.GraphSchemaRegistry schemaRegistry() {
        var registry = mock(com.harness.graph.schema.GraphSchemaRegistry.class);
        when(registry.require("schema-a")).thenReturn(new com.harness.graph.schema.GraphSchemaDefinition(
                "schema-a", 1, com.harness.graph.schema.GraphSchemaMode.STRICT,
                Map.of("Student", new com.harness.graph.schema.GraphNodeTypeDefinition("Student", Map.of()),
                        "Class", new com.harness.graph.schema.GraphNodeTypeDefinition("Class", Map.of())),
                Map.of("KNOWS", new com.harness.graph.schema.GraphRelationTypeDefinition(
                        "KNOWS", Set.of("Student"), Set.of("Student"), Map.of())), 1, 2));
        return registry;
    }

    private static GraphChangeSet changeSet() {
        return new GraphChangeSet(
                "request-1", "graph-a", "schema-a",
                List.of(
                        new GraphNode("student-1", Set.of("Student"),
                                Map.of("name", "Alice")),
                        new GraphNode("student-2", Set.of("Student"),
                                Map.of("name", "Bob"))),
                List.of(new GraphRelation(
                        "knows-1", "student-1", "student-2", "KNOWS", Map.of())),
                Set.of(), Set.of());
    }
}

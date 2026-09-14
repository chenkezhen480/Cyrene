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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersistentGraphSpaceWikiCompilerTest {

    @Test
    void createsOneCatalogEntryThatPointsToTheConcreteGraphSpace() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        when(repository.findById(any())).thenReturn(Optional.empty());
        PersistentGraphSpaceWikiCompiler compiler =
                new PersistentGraphSpaceWikiCompiler(repository, schemaRegistry(), describer());
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
                .contains("Graph Space graph-a", "Schema: schema-a", "Student", "Class", "KNOWS", "query_graph", "Typical queries")
                .doesNotContain("Alice", "Bob");
        assertThat(change.revision().description()).isEqualTo("AI description: discover Student and Class relationships using query_graph.");
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
    }

    @Test
    void existingStableGraphSpaceDoesNotCreateRevisionPerMutation() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        GraphCapabilityDescriber describer = describer();
        PersistentGraphSpaceWikiCompiler compiler =
                new PersistentGraphSpaceWikiCompiler(repository, schemaRegistry(), describer);
        when(repository.findById(any())).thenReturn(Optional.empty());
        compiler.synchronize(changeSet(),
                new GraphMutationResult("request-1", true, 2, 1));
        KnowledgeRevisionChange first = capture(repository);
        org.mockito.Mockito.clearInvocations(repository, describer);
        when(repository.findById(any())).thenReturn(Optional.of(
                new KnowledgeHead(first.concept(), first.revision())));

        compiler.synchronize(changeSet(),
                new GraphMutationResult("request-1", true, 2, 1));

        verify(repository, never()).commitChanges(any());
        verify(describer, never()).describe(any());
    }

    @Test
    void descriptionFailureDoesNotWriteAFakeSuccessfulWiki() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        when(repository.findById(any())).thenReturn(Optional.empty());
        GraphCapabilityDescriber describer = describer();
        when(describer.describe(any())).thenThrow(new IllegalStateException("model unavailable"));
        var compiler = new PersistentGraphSpaceWikiCompiler(repository, schemaRegistry(), describer);
        assertThatThrownBy(() -> compiler.synchronize(changeSet(), new GraphMutationResult("request-1", true, 2, 1)))
                .hasMessageContaining("model unavailable");
        verify(repository, never()).commitChanges(any());
    }

    private static GraphCapabilityDescriber describer() {
        GraphCapabilityDescriber describer = mock(GraphCapabilityDescriber.class);
        when(describer.describe(any())).thenReturn("AI description: discover Student and Class relationships using query_graph.");
        return describer;
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

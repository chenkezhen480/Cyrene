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
                new PersistentGraphSpaceWikiCompiler(repository);
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
                .contains("Graph Space graph-a", "Schema: schema-a", "Student", "KNOWS")
                .doesNotContain("Alice", "Bob");
        assertThat(change.revision().metadata())
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
        PersistentGraphSpaceWikiCompiler compiler =
                new PersistentGraphSpaceWikiCompiler(repository);
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

    private static KnowledgeRevisionChange capture(KnowledgeRepository repository) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeRevisionChange>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(repository).commitChanges(captor.capture());
        return captor.getValue().getFirst();
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

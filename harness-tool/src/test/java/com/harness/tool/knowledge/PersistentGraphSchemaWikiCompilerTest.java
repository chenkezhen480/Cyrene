package com.harness.tool.knowledge;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeIndexOperation;
import com.harness.core.knowledge.KnowledgeStatus;
import com.harness.graph.schema.GraphNodeTypeDefinition;
import com.harness.graph.schema.GraphPropertyDefinition;
import com.harness.graph.schema.GraphPropertyType;
import com.harness.graph.schema.GraphSchemaDefinition;
import com.harness.graph.schema.GraphSchemaDetails;
import com.harness.graph.schema.GraphSchemaFormat;
import com.harness.graph.schema.GraphSchemaMode;
import com.harness.graph.schema.GraphSchemaSource;
import com.harness.tool.knowledge.authority.KnowledgeHead;
import com.harness.tool.knowledge.authority.KnowledgeRepository;
import com.harness.tool.knowledge.authority.KnowledgeRevisionChange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersistentGraphSchemaWikiCompilerTest {

    private KnowledgeRepository repository;
    private PersistentGraphSchemaWikiCompiler compiler;

    @BeforeEach
    void setUp() {
        EnvConfig.init(Map.of(EnvKey.KNOWLEDGE_CATALOG_COLLECTION, "catalog"));
        repository = mock(KnowledgeRepository.class);
        compiler = new PersistentGraphSchemaWikiCompiler(repository);
    }

    @Test
    void synchronizesRegistrySchemaAsStableCatalogRevision() {
        when(repository.findById(any())).thenReturn(Optional.empty());

        compiler.synchronize(details(true));

        KnowledgeRevisionChange change = capturedChange();
        assertThat(change.concept().conceptType()).isEqualTo(KnowledgeConceptType.GRAPH_SCHEMA);
        assertThat(change.concept().status()).isEqualTo(KnowledgeStatus.STABLE);
        assertThat(change.revision().body())
                .contains("Graph Schema student-v1", "Student", "name:STRING required");
        assertThat(change.revision().metadata())
                .containsEntry("schemaId", "student-v1")
                .containsEntry("enabled", true)
                .containsEntry("resourceUri", "cyrene://graph-schemas/student-v1");
        assertThat(change.indexTasks()).singleElement()
                .satisfies(task -> {
                    assertThat(task.operation())
                            .isEqualTo(com.harness.core.knowledge.KnowledgeIndexOperation.UPSERT_CURRENT);
                    assertThat(task.operation()).isEqualTo(KnowledgeIndexOperation.UPSERT_CURRENT);
                });
    }

    @Test
    void deletionCreatesDeprecatedRevisionAndCatalogDelete() {
        KnowledgeRevisionChange initial = initialChange();
        clearInvocations(repository);
        when(repository.findById(any())).thenReturn(Optional.of(new KnowledgeHead(
                initial.concept(), initial.revision())));

        compiler.deprecate("student-v1");

        KnowledgeRevisionChange change = capturedChange();
        assertThat(change.expectedConceptVersion()).isEqualTo(1);
        assertThat(change.concept().status()).isEqualTo(KnowledgeStatus.DEPRECATED);
        assertThat(change.indexTasks()).singleElement()
                .satisfies(task -> assertThat(task.operation())
                        .isEqualTo(KnowledgeIndexOperation.DELETE_CONCEPT));
    }

    @Test
    void skipsUnchangedRegistryState() {
        KnowledgeRevisionChange initial = initialChange();
        clearInvocations(repository);
        when(repository.findById(any())).thenReturn(Optional.of(new KnowledgeHead(
                initial.concept(), initial.revision())));

        compiler.synchronize(details(false));

        verify(repository, never()).commitChanges(any());
    }

    private KnowledgeRevisionChange initialChange() {
        when(repository.findById(any())).thenReturn(Optional.empty());
        compiler.synchronize(details(false));
        return capturedChange();
    }

    @SuppressWarnings("unchecked")
    private KnowledgeRevisionChange capturedChange() {
        ArgumentCaptor<List<KnowledgeRevisionChange>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(repository).commitChanges(captor.capture());
        return captor.getValue().getFirst();
    }

    private static GraphSchemaDetails details(boolean enabled) {
        GraphSchemaDefinition definition = new GraphSchemaDefinition(
                "student-v1", 2, GraphSchemaMode.STRICT,
                Map.of("Student", new GraphNodeTypeDefinition(
                        "Student", Map.of("name", new GraphPropertyDefinition(
                                "name", GraphPropertyType.STRING,
                                true, false, true, true)))),
                Map.of(), 1, 3);
        return new GraphSchemaDetails(
                definition, enabled, GraphSchemaSource.MANAGED,
                GraphSchemaFormat.JSON, true, "{}");
    }
}

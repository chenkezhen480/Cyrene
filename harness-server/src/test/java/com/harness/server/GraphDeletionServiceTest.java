package com.harness.server;

import com.harness.agent.graph.GraphSpaceAccessService;
import com.harness.core.knowledge.KnowledgeConcept;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeNamespaceType;
import com.harness.core.knowledge.KnowledgeStatus;
import com.harness.core.model.PageInfo;
import com.harness.core.model.PageResponse;
import com.harness.graph.model.GraphDeleteResult;
import com.harness.graph.model.GraphSpaceKey;
import com.harness.graph.model.GraphSpaceSummary;
import com.harness.graph.schema.GraphNodeTypeDefinition;
import com.harness.graph.schema.GraphSchemaDetails;
import com.harness.graph.schema.GraphSchemaFormat;
import com.harness.graph.schema.GraphSchemaManagementService;
import com.harness.graph.schema.GraphSchemaMode;
import com.harness.graph.schema.GraphSchemaSource;
import com.harness.graph.store.KnowledgeGraphStore;
import com.harness.tool.knowledge.GraphSchemaWikiCompiler;
import com.harness.tool.knowledge.GraphSpaceWikiCompiler;
import com.harness.tool.knowledge.authority.KnowledgeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GraphDeletionServiceTest {

    private final KnowledgeGraphStore graphStore = mock(KnowledgeGraphStore.class);
    private final GraphSpaceAccessService graphAccess = mock(GraphSpaceAccessService.class);
    private final GraphSchemaManagementService schemaService =
            mock(GraphSchemaManagementService.class);
    private final GraphSchemaWikiCompiler schemaWiki = mock(GraphSchemaWikiCompiler.class);
    private final GraphSpaceWikiCompiler spaceWiki = mock(GraphSpaceWikiCompiler.class);
    private final KnowledgeRepository repository = mock(KnowledgeRepository.class);
    private final GraphDeletionService service = new GraphDeletionService(
            graphStore, graphAccess, schemaService, schemaWiki, spaceWiki, repository);

    @Test
    void deleteSpaceRemovesGraphDataBindingsAndTheWikiCard() {
        when(graphStore.providerName()).thenReturn("neo4j");
        when(graphStore.deleteGraphSpace(new GraphSpaceKey("students", "student-v1")))
                .thenReturn(new GraphDeleteResult(7, 4));
        when(graphAccess.deleteBindings("students", "student-v1")).thenReturn(2);

        var result = service.deleteSpace("students", "student-v1");

        assertThat(result.deletedNodes()).isEqualTo(7);
        assertThat(result.deletedRelations()).isEqualTo(4);
        assertThat(result.deletedBindings()).isEqualTo(2);
        verify(spaceWiki).deprecate("students", "student-v1");
    }

    @Test
    void deleteSpaceRefusesWhenTheGraphProviderIsDisabled() {
        when(graphStore.providerName()).thenReturn("none");

        assertThatThrownBy(() -> service.deleteSpace("students", "student-v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("graph provider");
        verifyNoInteractions(graphAccess, spaceWiki);
    }

    /**
     * Ordering is the load-bearing part: the Schema is fenced off before its spaces are cleared, and
     * the Wiki card is discontinued before the definition is deleted, so a retry can still find the
     * Schema and finish the cascade.
     */
    @Test
    void deleteSchemaCascadesSpacesThenCardThenDefinition() {
        when(schemaService.get("student-v1")).thenReturn(details(true, true));
        when(graphStore.providerName()).thenReturn("neo4j");
        when(graphStore.listGraphSpaces(any())).thenReturn(spacePage(
                new GraphSpaceSummary("students", "student-v1", 3, 2),
                new GraphSpaceSummary("teachers", "other-schema", 1, 0)));
        when(graphStore.deleteGraphSpace(new GraphSpaceKey("students", "student-v1")))
                .thenReturn(new GraphDeleteResult(3, 2));
        when(graphAccess.deleteBindings("students", "student-v1")).thenReturn(1);
        when(graphAccess.deleteBindingsBySchema("student-v1")).thenReturn(1);
        when(repository.findManagementPage(eq(KnowledgeConceptType.GRAPH_SPACE),
                eq(KnowledgeStatus.STABLE), any(), anyInt())).thenReturn(emptyConceptPage());

        var result = service.deleteSchema("student-v1");

        assertThat(result.deleted()).isTrue();
        assertThat(result.disabled()).isTrue();
        assertThat(result.deletedSpaces()).isEqualTo(1);
        assertThat(result.deletedNodes()).isEqualTo(3);
        assertThat(result.deletedRelations()).isEqualTo(2);
        assertThat(result.deletedBindings()).isEqualTo(2);

        InOrder order = inOrder(schemaService, graphStore, graphAccess, spaceWiki, schemaWiki);
        order.verify(schemaService).disable("student-v1");
        order.verify(graphStore).listGraphSpaces(any());
        order.verify(graphStore).deleteGraphSpace(new GraphSpaceKey("students", "student-v1"));
        order.verify(graphAccess).deleteBindings("students", "student-v1");
        order.verify(spaceWiki).deprecate("students", "student-v1");
        order.verify(graphAccess).deleteBindingsBySchema("student-v1");
        order.verify(schemaWiki).deprecate("student-v1");
        order.verify(schemaService).delete("student-v1");
        // A space of another Schema must not be touched.
        verify(graphStore, never()).deleteGraphSpace(new GraphSpaceKey("teachers", "other-schema"));
    }

    @Test
    void deleteSchemaLeavesAnAlreadyDisabledSchemaEnabledFlagAlone() {
        when(schemaService.get("student-v1")).thenReturn(details(false, true));
        when(graphStore.providerName()).thenReturn("neo4j");
        when(graphStore.listGraphSpaces(any())).thenReturn(spacePage());
        when(repository.findManagementPage(any(), any(), any(), anyInt()))
                .thenReturn(emptyConceptPage());

        var result = service.deleteSchema("student-v1");

        assertThat(result.disabled()).isFalse();
        verify(schemaService, never()).disable(any());
    }

    /**
     * A Graph Space that holds no nodes is invisible to the node-derived enumeration, so its card has
     * to be discontinued from the authority namespace instead — including when its graphId contains
     * the same ':' that separates it from the schemaId in the namespace key.
     */
    @Test
    void deleteSchemaDeprecatesCardsOfSpacesWithoutNodes() {
        when(schemaService.get("student-v1")).thenReturn(details(false, true));
        when(graphStore.providerName()).thenReturn("neo4j");
        when(graphStore.listGraphSpaces(any())).thenReturn(spacePage(
                new GraphSpaceSummary("students", "student-v1", 1, 0)));
        when(graphStore.deleteGraphSpace(any())).thenReturn(new GraphDeleteResult(1, 0));
        when(repository.findManagementPage(any(), any(), any(), anyInt()))
                .thenReturn(conceptPage(graphSpaceCard("erp:students:student-v1")));

        var result = service.deleteSchema("student-v1");

        assertThat(result.deletedSpaces()).isEqualTo(1);
        verify(spaceWiki).deprecate("students", "student-v1");
        verify(spaceWiki).deprecate("erp:students", "student-v1");
        InOrder order = inOrder(graphStore, graphAccess, spaceWiki, schemaWiki, schemaService);
        order.verify(graphStore).deleteGraphSpace(new GraphSpaceKey("students", "student-v1"));
        order.verify(spaceWiki).deprecate("students", "student-v1");
        order.verify(spaceWiki).deprecate("erp:students", "student-v1");
        order.verify(graphAccess).deleteBindingsBySchema("student-v1");
        order.verify(schemaWiki).deprecate("student-v1");
        order.verify(schemaService).delete("student-v1");
    }

    @Test
    void deleteSchemaRefusesWhenTheGraphProviderIsDisabled() {
        when(graphStore.providerName()).thenReturn("none");

        assertThatThrownBy(() -> service.deleteSchema("student-v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("graph provider");
        verifyNoInteractions(schemaService, schemaWiki, spaceWiki);
    }

    @Test
    void deleteSchemaPagesThroughEveryGraphSpaceOfTheSchema() {
        when(schemaService.get("student-v1")).thenReturn(details(false, true));
        when(graphStore.providerName()).thenReturn("neo4j");
        when(graphStore.listGraphSpaces(any()))
                .thenReturn(new PageResponse<>(
                                List.of(new GraphSpaceSummary("students", "student-v1", 1, 0)),
                                new PageInfo(100, "cursor-1", true)))
                .thenReturn(new PageResponse<>(
                                List.of(new GraphSpaceSummary("labs", "student-v1", 1, 0)),
                                new PageInfo(100, "", false)));
        when(graphStore.deleteGraphSpace(any())).thenReturn(new GraphDeleteResult(1, 0));
        when(repository.findManagementPage(any(), any(), any(), anyInt()))
                .thenReturn(emptyConceptPage());

        var result = service.deleteSchema("student-v1");

        assertThat(result.deletedSpaces()).isEqualTo(2);
        verify(graphStore).deleteGraphSpace(new GraphSpaceKey("students", "student-v1"));
        verify(graphStore).deleteGraphSpace(new GraphSpaceKey("labs", "student-v1"));
    }

    @Test
    void deleteSchemaFailsWhenSpacePaginationStalls() {
        when(schemaService.get("student-v1")).thenReturn(details(false, true));
        when(graphStore.providerName()).thenReturn("neo4j");
        when(graphStore.deleteGraphSpace(any())).thenReturn(new GraphDeleteResult(1, 0));
        // A store that keeps handing back the same cursor never reaches the remaining spaces.
        when(graphStore.listGraphSpaces(any()))
                .thenReturn(new PageResponse<>(
                        List.of(new GraphSpaceSummary("students", "student-v1", 1, 0)),
                        new PageInfo(100, "cursor-1", true)))
                .thenReturn(new PageResponse<>(
                        List.of(new GraphSpaceSummary("labs", "student-v1", 1, 0)),
                        new PageInfo(100, "cursor-1", true)));

        assertThatThrownBy(() -> service.deleteSchema("student-v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not advance");
    }

    @Test
    void deleteSchemaRefusesSpiSchemas() {
        when(schemaService.get("spi-schema")).thenReturn(details(true, false));

        assertThatThrownBy(() -> service.deleteSchema("spi-schema"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("read-only");
        verify(graphStore, never()).deleteGraphSpace(any());
        verifyNoInteractions(schemaWiki, spaceWiki);
    }

    private static GraphSchemaDetails details(boolean enabled, boolean editable) {
        return new GraphSchemaDetails(
                new com.harness.graph.schema.GraphSchemaDefinition(
                        "student-v1", 1, GraphSchemaMode.STRICT,
                        Map.of("Student", new GraphNodeTypeDefinition("Student", Map.of())),
                        Map.of(), 1, 2),
                enabled, GraphSchemaSource.MANAGED, GraphSchemaFormat.JSON, editable, "{}");
    }

    private static PageResponse<GraphSpaceSummary> spacePage(GraphSpaceSummary... items) {
        return new PageResponse<>(List.of(items), new PageInfo(100, "", false));
    }

    private static PageResponse<KnowledgeConcept> emptyConceptPage() {
        return new PageResponse<>(List.of(), new PageInfo(100, "", false));
    }

    private static PageResponse<KnowledgeConcept> conceptPage(KnowledgeConcept... items) {
        return new PageResponse<>(List.of(items), new PageInfo(100, "", false));
    }

    private static KnowledgeConcept graphSpaceCard(String namespaceKey) {
        Instant now = Instant.parse("2026-09-17T00:00:00Z");
        return new KnowledgeConcept(
                "concept-orphan", null, null, KnowledgeNamespaceType.GRAPH, namespaceKey,
                KnowledgeConceptType.GRAPH_SPACE, namespaceKey.substring(0, namespaceKey.indexOf(':')),
                KnowledgeStatus.STABLE, "revision-orphan", 1, null, now, now);
    }
}

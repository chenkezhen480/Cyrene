package com.harness.graph.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.GraphRequestContext;
import com.harness.graph.config.GraphProvider;
import com.harness.graph.config.GraphSettings;
import com.harness.graph.model.GraphNode;
import com.harness.graph.model.GraphPath;
import com.harness.graph.model.GraphRelation;
import com.harness.graph.model.GraphRouteResult;
import com.harness.graph.schema.GraphNodeTypeDefinition;
import com.harness.graph.schema.GraphPropertyDefinition;
import com.harness.graph.schema.GraphPropertyType;
import com.harness.graph.schema.GraphRelationTypeDefinition;
import com.harness.graph.schema.GraphSchemaDefinition;
import com.harness.graph.schema.GraphSchemaMode;
import com.harness.graph.schema.GraphSchemaProvider;
import com.harness.graph.schema.GraphSchemaRegistry;
import com.harness.graph.store.KnowledgeGraphStore;
import com.harness.tool.protocol.ToolEnvelope;
import com.harness.tool.protocol.ToolEnvelopeStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphRetrievalTest {

    @Test
    void anchoredRetrieverAlwaysUsesServerControlledScope() {
        KnowledgeGraphStore store = mock(KnowledgeGraphStore.class);
        GraphSchemaRegistry registry = registry();
        GraphSettings settings = settings();
        when(store.findNeighborhood(argThat(request ->
                request.graphId().equals("graph-1")
                        && request.schemaId().equals("project-graph")
                        && request.subjectIds().equals(Set.of("person-1")))))
                .thenReturn(GraphRouteResult.empty());
        AnchoredNeighborhoodGraphRetriever retriever =
                new AnchoredNeighborhoodGraphRetriever(store, registry, settings);

        retriever.retrieve(
                new GraphRequestContext(
                        "graph-1",
                        "project-graph",
                        Set.of("person-1"),
                        Set.of("anchored-neighborhood")
                ),
                "anchored-neighborhood",
                Set.of("WORKS_ON"),
                2,
                20
        );

        verify(store).findNeighborhood(argThat(request ->
                request.graphId().equals("graph-1")
                        && request.schemaId().equals("project-graph")
                        && request.subjectIds().equals(Set.of("person-1"))));
    }

    @Test
    void anchoredRetrieverRejectsUnregisteredQueryId() {
        AnchoredNeighborhoodGraphRetriever retriever =
                new AnchoredNeighborhoodGraphRetriever(
                        mock(KnowledgeGraphStore.class), registry(), settings());
        GraphRequestContext context = new GraphRequestContext(
                "graph-1",
                "project-graph",
                Set.of("person-1"),
                Set.of("anchored-neighborhood")
        );

        assertThatThrownBy(() -> retriever.retrieve(
                context, "raw-cypher", Set.of(), 1, 10))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void formatterRemovesSensitivePropertiesAndKeepsGraphSemantics() {
        GraphNode person = new GraphNode(
                "person-1",
                Set.of("Person"),
                Map.of("name", "Alex", "secret", "private-value")
        );
        GraphNode project = new GraphNode(
                "project-1",
                Set.of("Project"),
                Map.of("name", "Cyrene")
        );
        GraphRelation relation = new GraphRelation(
                "relation-1",
                "person-1",
                "project-1",
                "WORKS_ON",
                Map.of("role", "maintainer")
        );
        GraphRouteResult result = new GraphRouteResult(
                List.of(person, project),
                List.of(relation),
                List.of(),
                List.of(),
                null,
                Map.of()
        );

        ObjectMapper objectMapper = new ObjectMapper();
        ToolEnvelope<GraphToolData> envelope = new DefaultGraphResultFormatter(
                registry().require("project-graph"), settings(), objectMapper)
                .format("graph-1", result);

        assertThat(envelope.status()).isEqualTo(ToolEnvelopeStatus.SUCCESS);
        assertThat(envelope.data().graphId()).isEqualTo("graph-1");
        assertThat(envelope.data().schemaId()).isEqualTo("project-graph");
        assertThat(envelope.data().nodes()).hasSize(2);
        assertThat(envelope.data().relations()).hasSize(1);
        assertThat(envelope.data().nodes().getFirst().properties())
                .containsEntry("name", "Alex")
                .doesNotContainKey("secret");
        assertThat(envelope.data().relations().getFirst().properties())
                .containsEntry("role", "maintainer");
        assertThat(envelope.meta()).containsEntry("truncated", false);
        assertThat(objectMapper.valueToTree(envelope).toString())
                .doesNotContain("private-value", "chunk", "score");
    }

    @Test
    void formatterAppliesLimitsBeforeSerializationAndReturnsValidJson() throws Exception {
        GraphRouteResult result = new GraphRouteResult(
                List.of(
                        new GraphNode("person-1", Set.of("Person"), Map.of("name", "Alex")),
                        new GraphNode("project-1", Set.of("Project"), Map.of("name", "Cyrene"))
                ),
                List.of(),
                List.of(),
                List.of(),
                null,
                Map.of()
        );
        ObjectMapper objectMapper = new ObjectMapper();
        GraphSettings boundedSettings = settings(1, 500);

        ToolEnvelope<GraphToolData> envelope = new DefaultGraphResultFormatter(
                registry().require("project-graph"), boundedSettings, objectMapper)
                .format("graph-1", result);
        String json = objectMapper.writeValueAsString(envelope);

        assertThat(json.length()).isLessThanOrEqualTo(boundedSettings.contextMaxChars());
        assertThat(objectMapper.readTree(json).path("meta").path("truncated").asBoolean())
                .isTrue();
        assertThat(envelope.data().nodes()).hasSize(1);
    }

    @Test
    void formatterUsesTheSameEnvelopeForEmptyResults() {
        ToolEnvelope<GraphToolData> envelope = new DefaultGraphResultFormatter(
                registry().require("project-graph"), settings(), new ObjectMapper())
                .format("graph-1", GraphRouteResult.empty());

        assertThat(envelope.status()).isEqualTo(ToolEnvelopeStatus.EMPTY);
        assertThat(envelope.data().graphId()).isEqualTo("graph-1");
        assertThat(envelope.data().nodes()).isEmpty();
        assertThat(envelope.data().relations()).isEmpty();
        assertThat(envelope.data().paths()).isEmpty();
    }

    @Test
    void formatterFiltersSensitivePropertiesInsidePaths() {
        GraphNode person = new GraphNode(
                "person-1", Set.of("Person"), Map.of("name", "Alex", "secret", "hidden"));
        GraphNode project = new GraphNode(
                "project-1", Set.of("Project"), Map.of("name", "Cyrene"));
        GraphRelation relation = new GraphRelation(
                "relation-1", "person-1", "project-1", "WORKS_ON", Map.of("role", "maintainer"));
        GraphRouteResult result = new GraphRouteResult(
                List.of(),
                List.of(),
                List.of(new GraphPath(List.of(person, project), List.of(relation), 1)),
                List.of(),
                null,
                Map.of());

        ToolEnvelope<GraphToolData> envelope = new DefaultGraphResultFormatter(
                registry().require("project-graph"), settings(), new ObjectMapper())
                .format("graph-1", result);

        assertThat(envelope.data().paths()).hasSize(1);
        assertThat(envelope.data().paths().getFirst().nodes().getFirst().properties())
                .containsEntry("name", "Alex")
                .doesNotContainKey("secret");
    }

    @Test
    void formatterUsesCharacterLimitWithoutCuttingJson() throws Exception {
        GraphRouteResult result = new GraphRouteResult(
                List.of(new GraphNode(
                        "person-1", Set.of("Person"), Map.of("name", "A long display name"))),
                List.of(), List.of(), List.of(), null, Map.of());
        ObjectMapper objectMapper = new ObjectMapper();
        GraphSettings boundedSettings = settings(20, 180);

        ToolEnvelope<GraphToolData> envelope = new DefaultGraphResultFormatter(
                registry().require("project-graph"), boundedSettings, objectMapper)
                .format("graph-1", result);
        String json = objectMapper.writeValueAsString(envelope);

        assertThat(json.length()).isLessThanOrEqualTo(180);
        assertThat(objectMapper.readTree(json).path("meta").path("truncated").asBoolean())
                .isTrue();
        assertThat(envelope.data().nodes()).isEmpty();
    }

    static GraphSchemaRegistry registry() {
        GraphPropertyDefinition name = new GraphPropertyDefinition(
                "name", GraphPropertyType.STRING, true, false, true, true);
        GraphPropertyDefinition secret = new GraphPropertyDefinition(
                "secret", GraphPropertyType.STRING, false, true, false, false);
        GraphPropertyDefinition role = new GraphPropertyDefinition(
                "role", GraphPropertyType.STRING, false, false, true, false);
        GraphSchemaDefinition schema = new GraphSchemaDefinition(
                "project-graph",
                1,
                GraphSchemaMode.STRICT,
                Map.of(
                        "Person", new GraphNodeTypeDefinition(
                                "Person", Map.of("name", name, "secret", secret)),
                        "Project", new GraphNodeTypeDefinition(
                                "Project", Map.of("name", name))
                ),
                Map.of(
                        "WORKS_ON", new GraphRelationTypeDefinition(
                                "WORKS_ON",
                                Set.of("Person"),
                                Set.of("Project"),
                                Map.of("role", role))
                ),
                1,
                2
        );
        return new GraphSchemaRegistry(List.of(new GraphSchemaProvider() {
            @Override
            public String schemaId() {
                return schema.schemaId();
            }

            @Override
            public GraphSchemaDefinition definition() {
                return schema;
            }
        }));
    }

    static GraphSettings settings() {
        return settings(20, 4_000);
    }

    static GraphSettings settings(int contextMaxItems, int contextMaxChars) {
        return new GraphSettings(
                GraphProvider.NONE,
                "",
                "",
                "",
                "",
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                10,
                20,
                100,
                1,
                2,
                contextMaxItems,
                contextMaxChars
        );
    }
}

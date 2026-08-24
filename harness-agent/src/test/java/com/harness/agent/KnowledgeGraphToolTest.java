package com.harness.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.graph.GraphSpaceAccessService;
import com.harness.agent.graph.GraphSpaceReference;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.GraphRequestContext;
import com.harness.core.model.PageInfo;
import com.harness.core.model.PageResponse;
import com.harness.core.model.ToolResult;
import com.harness.graph.config.GraphProvider;
import com.harness.graph.config.GraphSettings;
import com.harness.graph.model.GraphRouteResult;
import com.harness.graph.model.GraphNodePageRequest;
import com.harness.graph.retrieval.AnchoredNeighborhoodGraphRetriever;
import com.harness.graph.retrieval.GraphKnowledgeRetriever;
import com.harness.graph.schema.GraphSchemaDefinition;
import com.harness.graph.schema.GraphSchemaRegistry;
import com.harness.graph.store.KnowledgeGraphStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeGraphToolTest {

    @Mock
    private GraphKnowledgeRetriever retriever;
    @Mock
    private KnowledgeGraphStore graphStore;
    @Mock
    private GraphSpaceAccessService graphSpaceAccessService;
    @Mock
    private GraphSchemaRegistry schemaRegistry;

    private ObjectMapper objectMapper;
    private KnowledgeGraphTool tool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        GraphSettings settings = new GraphSettings(
                GraphProvider.NONE,
                "",
                "",
                "",
                "",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                1,
                10,
                20,
                1,
                2,
                20,
                4_000
        );
        tool = new KnowledgeGraphTool(
                retriever,
                graphStore,
                graphSpaceAccessService,
                schemaRegistry,
                settings,
                objectMapper
        );
    }

    @AfterEach
    void clearThreadLocals() {
        KnowledgeGraphTool.clearCurrentContext();
        ToolResult.clearCurrentStatus();
    }

    @Test
    void listsReadableGraphSpacesWithoutServerScope() throws Exception {
        var page = new PageResponse<>(
                List.of(new GraphSpaceReference(
                        "students",
                        "student-capability-v1",
                        "Student, teacher, and capability relationships"
                )),
                new PageInfo(10, "", false)
        );
        when(graphSpaceAccessService.listReadable(
                eq("tenant-1"),
                eq(10),
                eq("")
        )).thenReturn(page);
        when(schemaRegistry.find("student-capability-v1"))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(GraphSchemaDefinition.class)));
        KnowledgeGraphTool.setCurrentContext("tenant-1", null);

        String output = tool.execute(objectMapper.createObjectNode()
                .put("action", KnowledgeGraphTool.ACTION_LIST_GRAPH_SPACES));

        var envelope = objectMapper.readTree(output);
        assertThat(envelope.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(envelope.path("data").path("graphSpaces")).hasSize(1);
        assertThat(envelope.at("/data/graphSpaces/0/graphId").asText())
                .isEqualTo("students");
        assertThat(envelope.at("/data/graphSpaces/0/schemaId").asText())
                .isEqualTo("student-capability-v1");
        assertThat(envelope.at("/data/graphSpaces/0/description").asText())
                .isEqualTo("Student, teacher, and capability relationships");
        assertThat(envelope.at("/pageInfo/limit").asInt()).isEqualTo(10);
        assertThat(envelope.at("/meta/truncated").asBoolean()).isFalse();
        assertThat(ToolResult.consumeCurrentStatus())
                .isEqualTo(ToolResult.ResultStatus.SUCCESS);
    }

    @Test
    void rejectsSemanticallyIdenticalRepeatedCallsWithinOneAgentRequest() {
        when(graphSpaceAccessService.listReadable("tenant-1", 10, ""))
                .thenReturn(new PageResponse<>(List.of(), new PageInfo(10, "", false)));
        KnowledgeGraphTool.setCurrentContext("tenant-1", null);

        var firstArguments = objectMapper.createObjectNode()
                .put("action", KnowledgeGraphTool.ACTION_LIST_GRAPH_SPACES)
                .put("limit", 10);
        var repeatedArguments = objectMapper.createObjectNode()
                .put("limit", 10)
                .put("action", KnowledgeGraphTool.ACTION_LIST_GRAPH_SPACES);

        tool.execute(firstArguments);

        assertThatThrownBy(() -> tool.execute(repeatedArguments))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("identical knowledge graph call already ran");
        verify(graphSpaceAccessService).listReadable("tenant-1", 10, "");
    }

    @Test
    void hidesGraphSpacesWhoseSchemasAreNotRegistered() throws Exception {
        when(graphSpaceAccessService.listReadable("tenant-1", 10, ""))
                .thenReturn(new PageResponse<>(
                        List.of(new GraphSpaceReference(
                                "orphaned-space", "deleted-schema", "stale data")),
                        new PageInfo(10, "", false)
                ));
        KnowledgeGraphTool.setCurrentContext("tenant-1", null);

        String output = tool.execute(objectMapper.createObjectNode()
                .put("action", KnowledgeGraphTool.ACTION_LIST_GRAPH_SPACES));

        var envelope = objectMapper.readTree(output);
        assertThat(envelope.path("status").asText()).isEqualTo("EMPTY");
        assertThat(envelope.path("data").path("graphSpaces")).isEmpty();
        assertThat(envelope.at("/pageInfo/hasMore").asBoolean()).isFalse();
        assertThat(ToolResult.consumeCurrentStatus())
                .isEqualTo(ToolResult.ResultStatus.EMPTY);
    }

    @Test
    void continuesPaginationPastUnregisteredGraphSpaces() throws Exception {
        when(graphSpaceAccessService.listReadable("tenant-1", 10, ""))
                .thenReturn(new PageResponse<>(
                        List.of(new GraphSpaceReference(
                                "orphaned-space", "deleted-schema", "stale data")),
                        new PageInfo(10, "next-page", true)
                ));
        when(graphSpaceAccessService.listReadable("tenant-1", 10, "next-page"))
                .thenReturn(new PageResponse<>(
                        List.of(new GraphSpaceReference(
                                "students", "student-schema", "student capabilities")),
                        new PageInfo(10, "", false)
                ));
        GraphSchemaDefinition registeredSchema =
                org.mockito.Mockito.mock(GraphSchemaDefinition.class);
        when(schemaRegistry.find(any())).thenAnswer(invocation ->
                "student-schema".equals(invocation.getArgument(0))
                        ? Optional.of(registeredSchema)
                        : Optional.empty());
        KnowledgeGraphTool.setCurrentContext("tenant-1", null);

        String output = tool.execute(objectMapper.createObjectNode()
                .put("action", KnowledgeGraphTool.ACTION_LIST_GRAPH_SPACES));

        var graphSpaces = objectMapper.readTree(output).at("/data/graphSpaces");
        assertThat(graphSpaces).hasSize(1);
        assertThat(graphSpaces.get(0).path("graphId").asText()).isEqualTo("students");
        assertThat(graphSpaces.get(0).path("schemaId").asText()).isEqualTo("student-schema");
        assertThat(graphSpaces.toString())
                .doesNotContain("orphaned-space", "deleted-schema");
        verify(graphSpaceAccessService).listReadable("tenant-1", 10, "next-page");
    }

    @Test
    void autonomouslyRetrievesNeighborhoodAfterAuthorization() throws Exception {
        stubSchema("student-capability-v1");
        when(retriever.retrieve(
                any(GraphRequestContext.class),
                eq(AnchoredNeighborhoodGraphRetriever.QUERY_ID),
                eq(Set.of()),
                eq(0),
                eq(0)
        )).thenReturn(GraphRouteResult.empty());
        KnowledgeGraphTool.setCurrentContext("tenant-1", null);

        var arguments = objectMapper.createObjectNode()
                .put("action", KnowledgeGraphTool.ACTION_FIND_NEIGHBORHOOD)
                .put("graphId", "students")
                .put("schemaId", "student-capability-v1");
        arguments.putArray("subjectIds").add("student-1");

        String output = tool.execute(arguments);

        var envelope = objectMapper.readTree(output);
        assertThat(envelope.path("status").asText()).isEqualTo("EMPTY");
        assertThat(envelope.at("/data/graphId").asText()).isEqualTo("students");
        assertThat(envelope.at("/data/schemaId").asText())
                .isEqualTo("student-capability-v1");
        assertThat(envelope.at("/data/nodes")).isEmpty();
        assertThat(envelope.at("/data/relations")).isEmpty();
        assertThat(envelope.at("/data/paths")).isEmpty();
        verify(graphSpaceAccessService).requireReadable(
                "tenant-1",
                "students",
                "student-capability-v1"
        );
        verify(retriever).retrieve(
                eq(new GraphRequestContext(
                        "students",
                        "student-capability-v1",
                        Set.of("student-1"),
                        Set.of(AnchoredNeighborhoodGraphRetriever.QUERY_ID)
                )),
                eq(AnchoredNeighborhoodGraphRetriever.QUERY_ID),
                eq(Set.of()),
                eq(0),
                eq(0)
        );
    }

    @Test
    void keepsServerScopeAuthoritative() {
        stubSchema("student-capability-v1");
        GraphRequestContext serverContext = new GraphRequestContext(
                "students",
                "student-capability-v1",
                Set.of("student-1"),
                Set.of(AnchoredNeighborhoodGraphRetriever.QUERY_ID)
        );
        when(retriever.retrieve(
                eq(serverContext),
                eq(AnchoredNeighborhoodGraphRetriever.QUERY_ID),
                eq(Set.of()),
                eq(0),
                eq(0)
        )).thenReturn(GraphRouteResult.empty());
        KnowledgeGraphTool.setCurrentContext("tenant-1", serverContext);

        tool.execute(objectMapper.createObjectNode());

        verify(graphSpaceAccessService).requireReadable(
                "tenant-1",
                "students",
                "student-capability-v1"
        );
        verify(retriever).retrieve(
                serverContext,
                AnchoredNeighborhoodGraphRetriever.QUERY_ID,
                Set.of(),
                0,
                0
        );
    }

    @Test
    void serverScopeExposesOnlyNeighborhoodRetrievalParameters() {
        GraphRequestContext serverContext = new GraphRequestContext(
                "students",
                "student-capability-v1",
                Set.of("student-1"),
                Set.of(AnchoredNeighborhoodGraphRetriever.QUERY_ID)
        );
        KnowledgeGraphTool.setCurrentContext("tenant-1", serverContext);

        var properties = tool.spec().parameters().path("properties");

        assertThat(properties.path("action").path("enum").toString())
                .isEqualTo("[\"findNeighborhood\"]");
        assertThat(properties.has("relationTypes")).isTrue();
        assertThat(properties.has("queryId")).isTrue();
        assertThat(properties.has("maxDepth")).isTrue();
        assertThat(properties.has("limit")).isTrue();
        assertThat(properties.has("graphId")).isFalse();
        assertThat(properties.has("schemaId")).isFalse();
        assertThat(properties.has("subjectIds")).isFalse();
        assertThat(properties.has("name")).isFalse();
        assertThat(properties.has("cursor")).isFalse();
    }

    @Test
    void serverScopeRejectsDiscoveryActionsAtExecutionBoundary() {
        GraphRequestContext serverContext = new GraphRequestContext(
                "students",
                "student-capability-v1",
                Set.of("student-1"),
                Set.of(AnchoredNeighborhoodGraphRetriever.QUERY_ID)
        );
        KnowledgeGraphTool.setCurrentContext("tenant-1", serverContext);

        assertThatThrownBy(() -> tool.execute(objectMapper.createObjectNode()
                .put("action", KnowledgeGraphTool.ACTION_LIST_GRAPH_SPACES)))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("only allows findNeighborhood");
    }

    @Test
    void graphSpaceScopeExposesNodeDiscoveryThenNeighborhood() {
        GraphRequestContext graphSpaceContext = new GraphRequestContext(
                "students",
                "student-capability-v1",
                Set.of(),
                Set.of(AnchoredNeighborhoodGraphRetriever.QUERY_ID)
        );
        KnowledgeGraphTool.setCurrentContext("tenant-1", graphSpaceContext);

        var properties = tool.spec().parameters().path("properties");

        assertThat(properties.path("action").path("enum").toString())
                .isEqualTo("[\"findNodes\",\"findNeighborhood\"]");
        assertThat(properties.has("name")).isTrue();
        assertThat(properties.has("label")).isTrue();
        assertThat(properties.has("subjectIds")).isTrue();
        assertThat(properties.has("graphId")).isFalse();
        assertThat(properties.has("schemaId")).isFalse();
    }

    @Test
    void graphSpaceScopeDefaultsToNodeDiscoveryUsingServerIdentifiers() throws Exception {
        stubSchema("student-capability-v1");
        GraphRequestContext graphSpaceContext = new GraphRequestContext(
                "students",
                "student-capability-v1",
                Set.of(),
                Set.of(AnchoredNeighborhoodGraphRetriever.QUERY_ID)
        );
        when(graphStore.listNodes(any())).thenReturn(new PageResponse<>(
                List.of(), new PageInfo(10, "", false)));
        KnowledgeGraphTool.setCurrentContext("tenant-1", graphSpaceContext);

        String output = tool.execute(objectMapper.createObjectNode().put("name", "Xiaoming"));

        var envelope = objectMapper.readTree(output);
        assertThat(envelope.path("status").asText()).isEqualTo("EMPTY");
        assertThat(envelope.at("/data/graphId").asText()).isEqualTo("students");
        assertThat(envelope.at("/data/schemaId").asText())
                .isEqualTo("student-capability-v1");
        assertThat(envelope.at("/data/nodes")).isEmpty();
        assertThat(envelope.at("/pageInfo/limit").asInt()).isEqualTo(10);
        verify(graphSpaceAccessService).requireReadable(
                "tenant-1", "students", "student-capability-v1");
        ArgumentCaptor<GraphNodePageRequest> requestCaptor =
                ArgumentCaptor.forClass(GraphNodePageRequest.class);
        verify(graphStore).listNodes(requestCaptor.capture());
        assertThat(requestCaptor.getValue().graphId()).isEqualTo("students");
        assertThat(requestCaptor.getValue().schemaId()).isEqualTo("student-capability-v1");
        assertThat(requestCaptor.getValue().name()).isEqualTo("Xiaoming");
    }

    private void stubSchema(String schemaId) {
        GraphSchemaDefinition schema = org.mockito.Mockito.mock(GraphSchemaDefinition.class);
        when(schema.schemaId()).thenReturn(schemaId);
        when(schemaRegistry.require(schemaId)).thenReturn(schema);
    }
}

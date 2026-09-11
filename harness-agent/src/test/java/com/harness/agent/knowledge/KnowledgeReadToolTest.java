package com.harness.agent.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.KnowledgeGraphTool;
import com.harness.agent.context.KnowledgeAccessService;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.knowledge.KnowledgeConcept;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeHandle;
import com.harness.core.knowledge.KnowledgeHandleCodec;
import com.harness.core.knowledge.KnowledgeNamespaceType;
import com.harness.core.knowledge.KnowledgeRevision;
import com.harness.core.knowledge.KnowledgeRouteTarget;
import com.harness.core.knowledge.KnowledgeStatus;
import com.harness.core.model.KnowledgeRequestContext;
import com.harness.core.model.ResultStatus;
import com.harness.core.model.ToolExecutionOutcome;
import com.harness.core.model.ToolOutput;
import com.harness.core.model.ToolSpec;
import com.harness.tool.Tool;
import com.harness.tool.ToolRegistry;
import com.harness.tool.knowledge.authority.KnowledgeHead;
import com.harness.tool.knowledge.authority.KnowledgeRepository;
import com.harness.tool.rag.RagRetriever;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeReadToolTest {

    private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");

    private com.harness.tool.knowledge.index.KnowledgeProjectionStore projectionStore;
    private KnowledgeRepository repository;
    private KnowledgeReadTool tool;
    private KnowledgeHandleCodec codec;
    private ObjectMapper objectMapper;
    private KnowledgeAccessService documentExecutor;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        repository = mock(KnowledgeRepository.class);
        projectionStore = mock(com.harness.tool.knowledge.index.KnowledgeProjectionStore.class);
        objectMapper = new ObjectMapper();
        codec = new KnowledgeHandleCodec(objectMapper);
        documentExecutor = mock(KnowledgeAccessService.class);
        when(documentExecutor.contextWindowMax()).thenReturn(2);
        tool = new KnowledgeReadTool(
                repository, projectionStore, codec, documentExecutor, null, objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));

        registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolSpec spec() {
                return tool.spec();
            }

            @Override
            public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
                return "";
            }
        });
        KnowledgeToolRuntimeContext.activate(
                "tenant-a", "user-a", null, null, registry.snapshot());
    }

    @AfterEach
    void clearContext() {
        KnowledgeToolRuntimeContext.clear();
    }

    @Test
    void hydratingRevisionPreservesAuthorityRoute() {
        KnowledgeHead original = playbookHead("playbook-1", "revision-1", "tenant-a");
        KnowledgeHead authority = new KnowledgeHead(original.concept(), original.currentRevision(),
                KnowledgeRouteTarget.OPERATION_MEMORY, Map.of("memoryId", "stored-memory-1"));

        KnowledgeHead hydrated = authority.withRevision(original.currentRevision());

        assertThat(hydrated.currentVersion()).isEqualTo("revision-1");
        assertThat(hydrated.routeType()).isEqualTo(KnowledgeRouteTarget.OPERATION_MEMORY);
        assertThat(hydrated.routeText("memoryId")).isEqualTo("stored-memory-1");
    }

    @Test
    void rejectsHandleWhoseRevisionIsNoLongerCurrent() {
        when(repository.findAuthorityById("playbook-1"))
                .thenReturn(Optional.of(playbookHead("playbook-1", "revision-2", "tenant-a")));
        KnowledgeHandle stale = KnowledgeHandle.concept(
                KnowledgeConceptType.OPERATION_PLAYBOOK, "playbook-1", "revision-1",
                KnowledgeRouteTarget.OPERATION_MEMORY);

        assertThatThrownBy(() -> tool.execute(objectMapper.createObjectNode()
                .put("handle", codec.encode(stale))))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("stale or no longer current");
    }

    @Test
    void rejectsHandleForAnotherTenantEvenWhenIdsAreKnown() {
        when(repository.findAuthorityById("playbook-2"))
                .thenReturn(Optional.of(playbookHead("playbook-2", "revision-2", "tenant-b")));
        KnowledgeHandle foreign = KnowledgeHandle.concept(
                KnowledgeConceptType.OPERATION_PLAYBOOK, "playbook-2", "revision-2",
                KnowledgeRouteTarget.OPERATION_MEMORY);

        assertThatThrownBy(() -> tool.execute(objectMapper.createObjectNode()
                .put("handle", codec.encode(foreign))))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("owner or tenant scope");
    }

    @Test
    void returnsAuthorizedCurrentOperationMemoryRevision() throws Exception {
        when(repository.findAuthorityById("playbook-3"))
                .thenReturn(Optional.of(playbookHead("playbook-3", "revision-3", "tenant-a")));
        var block = new com.harness.tool.knowledge.index.KnowledgeProjection(
                "revision-3", "playbook-3", "revision-3", "tenant-a", null,
                KnowledgeNamespaceType.OPERATION_MEMORY, null, KnowledgeConceptType.OPERATION_PLAYBOOK,
                KnowledgeRouteTarget.OPERATION_MEMORY, "cyrene://memory/playbook-3", "Title", "Summary",
                "vector playbook body", NOW, null, null, null, List.of(), null);
        when(projectionStore.findMemory(KnowledgeConceptType.OPERATION_PLAYBOOK,
                "playbook-3", "revision-3")).thenReturn(Optional.of(block));
        KnowledgeHandle current = KnowledgeHandle.concept(
                KnowledgeConceptType.OPERATION_PLAYBOOK, "playbook-3", "revision-3",
                KnowledgeRouteTarget.OPERATION_MEMORY);

        var output = objectMapper.readTree(tool.execute(objectMapper.createObjectNode()
                .put("handle", codec.encode(current))));

        assertThat(output.path("data").path("revisionId").asText())
                .isEqualTo("revision-3");
        assertThat(output.path("data").path("body").asText()).isEqualTo("vector playbook body");
    }

    @Test
    void rejectsTamperedDocumentCollectionBeforeStorageRead() {
        KnowledgeHead document = documentHead();
        when(repository.findAuthorityById(document.concept().id())).thenReturn(Optional.of(document));
        activateDocumentScope();
        KnowledgeHandle tampered = KnowledgeHandle.document(
                KnowledgeConceptType.SOURCE_DOCUMENT, document.concept().id(),
                document.currentRevision().id(), "foreign-collection",
                document.concept().id(), 0);

        assertThatThrownBy(() -> tool.execute(objectMapper.createObjectNode()
                .put("handle", codec.encode(tampered))))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("collection scope");
        verify(documentExecutor, never()).readContext(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void readsDocumentWindowUsingAuthorizedRevisionConstraint() throws Exception {
        KnowledgeHead document = documentHead();
        when(repository.findAuthorityById(document.concept().id())).thenReturn(Optional.of(document));
        when(documentExecutor.readContext(
                "collection-a", "document-1", "document-revision", 0, 0, 0))
                .thenReturn(List.of(new RagRetriever.RagDocument(
                        "chunk-0", "current document body", "manual.md", 0.0,
                        Map.of("document_id", "document-1",
                                "revision_id", "document-revision"), 0)));
        activateDocumentScope();
        KnowledgeHandle handle = KnowledgeHandle.document(
                KnowledgeConceptType.SOURCE_DOCUMENT, "document-1",
                "document-revision", "collection-a", "document-1", 0);

        var output = objectMapper.readTree(tool.execute(objectMapper.createObjectNode()
                .put("handle", codec.encode(handle)).put("before", 0).put("after", 0)));

        assertThat(output.at("/data/chunks/0/content").asText())
                .isEqualTo("current document body");
        verify(documentExecutor).readContext(
                "collection-a", "document-1", "document-revision", 0, 0, 0);
    }

    @Test
    void graphHandleRoutesThroughNeo4jExecutorAfterMysqlReauthorization() throws Exception {
        KnowledgeGraphTool graphExecutor = mock(KnowledgeGraphTool.class);
        KnowledgeHead schema = head(
                "schema-concept", "schema-revision", "tenant-a",
                KnowledgeNamespaceType.GRAPH, "schema-a",
                KnowledgeConceptType.GRAPH_SCHEMA, "schema wiki body");
        when(repository.findAuthorityById(schema.concept().id())).thenReturn(Optional.of(schema));
        when(graphExecutor.executeForWiki(
                org.mockito.ArgumentMatchers.eq("schema-a"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(ToolExecutionOutcome.succeeded(
                        ToolOutput.text("{\"status\":\"success\",\"data\":{\"graphSpaces\":[]}}"),
                        ResultStatus.EMPTY));
        KnowledgeReadTool graphTool = new KnowledgeReadTool(
                repository, projectionStore, codec, documentExecutor, graphExecutor, objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
        KnowledgeHandle handle = KnowledgeHandle.concept(
                KnowledgeConceptType.GRAPH_SCHEMA, schema.concept().id(),
                schema.currentRevision().id(), KnowledgeRouteTarget.GRAPH);

        var output = objectMapper.readTree(graphTool.execute(objectMapper.createObjectNode()
                .put("handle", codec.encode(handle))
                .put("graphAction", "listGraphSpaces")));

        assertThat(output.path("status").asText()).isEqualTo("EMPTY");
        assertThat(output.at("/data/schemaId").asText()).isEqualTo("schema-a");
        assertThat(output.at("/data/graphResult/data/graphSpaces").isArray()).isTrue();
        verify(graphExecutor).executeForWiki(
                org.mockito.ArgumentMatchers.eq("schema-a"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void graphSpaceHandlePinsTheExactGraphId() throws Exception {
        KnowledgeGraphTool graphExecutor = mock(KnowledgeGraphTool.class);
        KnowledgeHead graphSpace = head(
                "space-concept", "space-revision", "tenant-a",
                KnowledgeNamespaceType.GRAPH, "graph-a:schema-a",
                KnowledgeConceptType.GRAPH_SPACE, "graph space wiki body",
                Map.of("graphId", "graph-a", "schemaId", "schema-a"));
        when(repository.findAuthorityById(graphSpace.concept().id()))
                .thenReturn(Optional.of(graphSpace));
        when(graphExecutor.executeForWiki(
                org.mockito.ArgumentMatchers.eq("schema-a"),
                org.mockito.ArgumentMatchers.eq("graph-a"),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(ToolExecutionOutcome.succeeded(
                        ToolOutput.text("{\"status\":\"success\",\"data\":{\"nodes\":[]}}"),
                        ResultStatus.EMPTY));
        KnowledgeReadTool graphTool = new KnowledgeReadTool(
                repository, projectionStore, codec, documentExecutor, graphExecutor, objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
        KnowledgeHandle handle = KnowledgeHandle.concept(
                KnowledgeConceptType.GRAPH_SPACE, graphSpace.concept().id(),
                graphSpace.currentRevision().id(), KnowledgeRouteTarget.GRAPH);

        var output = objectMapper.readTree(graphTool.execute(objectMapper.createObjectNode()
                .put("handle", codec.encode(handle))));

        assertThat(output.at("/data/schemaId").asText()).isEqualTo("schema-a");
        assertThat(output.at("/data/graphId").asText()).isEqualTo("graph-a");
        verify(graphExecutor).executeForWiki(
                org.mockito.ArgumentMatchers.eq("schema-a"),
                org.mockito.ArgumentMatchers.eq("graph-a"),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unifiedReadSchemaOwnsGraphTraversalParameters() {
        KnowledgeGraphTool graphExecutor = mock(KnowledgeGraphTool.class);
        KnowledgeReadTool graphTool = new KnowledgeReadTool(
                repository, projectionStore, codec, documentExecutor, graphExecutor, objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));

        var properties = graphTool.spec().parameters().path("properties");

        assertThat(properties.path("graphAction").path("enum").toString())
                .isEqualTo("[\"listGraphSpaces\",\"findNodes\",\"findNeighborhood\"]");
        assertThat(properties.has("graphId")).isTrue();
        assertThat(properties.has("name")).isTrue();
        assertThat(properties.has("subjectIds")).isTrue();
        assertThat(properties.has("relationTypes")).isTrue();
        assertThat(properties.has("maxDepth")).isTrue();
    }

    private void activateDocumentScope() {
        KnowledgeToolRuntimeContext.activate(
                "tenant-a", "user-a",
                new KnowledgeRequestContext("collection-a", Set.of("document-1")),
                null, registry.snapshot());
    }

    private static KnowledgeHead playbookHead(
            String conceptId,
            String revisionId,
            String tenantId
    ) {
        return head(conceptId, revisionId, tenantId, KnowledgeNamespaceType.OPERATION_MEMORY,
                null, KnowledgeConceptType.OPERATION_PLAYBOOK, "playbook body");
    }

    private static KnowledgeHead documentHead() {
        return head("document-1", "document-revision", "tenant-a",
                KnowledgeNamespaceType.COLLECTION, "collection-a",
                KnowledgeConceptType.SOURCE_DOCUMENT, "document body");
    }

    private static KnowledgeHead head(
            String conceptId,
            String revisionId,
            String tenantId,
            KnowledgeNamespaceType namespaceType,
            String namespaceKey,
            KnowledgeConceptType conceptType,
            String body
    ) {
        return head(conceptId, revisionId, tenantId, namespaceType,
                namespaceKey, conceptType, body, Map.of());
    }

    private static KnowledgeHead head(
            String conceptId,
            String revisionId,
            String tenantId,
            KnowledgeNamespaceType namespaceType,
            String namespaceKey,
            KnowledgeConceptType conceptType,
            String body,
            Map<String, Object> metadata
    ) {
        KnowledgeConcept concept = new KnowledgeConcept(
                conceptId, tenantId, null, namespaceType, namespaceKey, conceptType,
                null, KnowledgeStatus.STABLE, revisionId, 1, null,
                NOW.minusSeconds(60), NOW.minusSeconds(30));
        KnowledgeRevision revision = new KnowledgeRevision(
                revisionId, conceptId, 1, "Title", null, body, "test/compiler",
                NOW.minusSeconds(30),
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                metadata, NOW.minusSeconds(30));
        return new KnowledgeHead(concept, revision);
    }
}

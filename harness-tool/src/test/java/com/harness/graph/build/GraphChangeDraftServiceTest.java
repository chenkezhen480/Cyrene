package com.harness.graph.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.PageInfo;
import com.harness.core.model.PageResponse;
import com.harness.graph.model.*;
import com.harness.graph.schema.*;
import com.harness.graph.store.KnowledgeGraphStore;
import com.harness.tool.artifact.ArtifactStorageService;
import com.harness.tool.artifact.FilesystemArtifactStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GraphChangeDraftServiceTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();
    final KnowledgeGraphStore graphStore = mock(KnowledgeGraphStore.class);
    final GraphMutationCommitter committer = mock(GraphMutationCommitter.class);
    final GraphDraftAccess access = mock(GraphDraftAccess.class);
    final GraphDraftScope scope = new GraphDraftScope("000000", "user", "session", "run", "trace", null, null);
    final GraphSchemaRegistry schemas = new GraphSchemaRegistry();
    ArtifactStorageService artifacts;
    GraphChangeDraftService service;

    @BeforeEach void setup() {
        schemas.register(new GraphSchemaDefinition("schema", 1, GraphSchemaMode.STRICT,
                Map.of("Person", new GraphNodeTypeDefinition("Person", Map.of(
                        "name", new GraphPropertyDefinition("name", GraphPropertyType.STRING, true, false, true, true),
                        "age", new GraphPropertyDefinition("age", GraphPropertyType.INTEGER, false, false, true, true)))),
                Map.of("KNOWS", new GraphRelationTypeDefinition("KNOWS", Set.of("Person"), Set.of("Person"), Map.of())), 1, 2));
        artifacts = new ArtifactStorageService(new FilesystemArtifactStore(directory), directory, 5);
        service = createService();
        when(graphStore.getNode(any())).thenAnswer(invocation -> {
            GraphNodeKey key = invocation.getArgument(0);
            return key.nodeId().equals("old") ? new GraphNode("old", Set.of("Person"), Map.of("name", "original", "age", 10)) : null;
        });
        when(graphStore.listIncidentRelations(anyString(), anyString(), anySet(), anyInt(), anyString()))
                .thenReturn(new PageResponse<>(List.of(), new PageInfo(50, "", false)));
        when(committer.commit(any())).thenAnswer(invocation -> {
            GraphChangeSet change = invocation.getArgument(0);
            return new GraphMutationResult(change.requestId(), true, change.nodes().size(), change.relations().size());
        });
    }

    GraphChangeDraftService createService() {
        return new GraphChangeDraftService(artifacts, graphStore, schemas,
                new CanonicalJsonGraphDataConverter(mapper), committer, access, mapper);
    }

    GraphDraftPrepareRequest request(String source, String hash, String nodes, String relations,
                                     Set<String> deleted, Set<String> discarded) throws Exception {
        return new GraphDraftPrepareRequest("graph", "schema", source, hash,
                mapper.readTree(nodes), mapper.readTree(relations), deleted, Set.of(), discarded);
    }

    @Test void unchangedIntegerReadBackFromNeo4jSurvivesDraftPrepareReloadAndApply() throws Exception {
        Object age = org.neo4j.driver.Values.value(10).asObject();
        assertThat(age).isInstanceOf(Long.class);
        doReturn(new GraphNode("old", Set.of("Person"), Map.of("name", "original", "age", age)))
                .when(graphStore).getNode(any());

        GraphDraftView draft = service.prepare(scope, request(null, null,
                "[{\"nodeId\":\"old\",\"properties\":{\"name\":\"changed\"}}]", "[]", Set.of(), Set.of()));
        GraphChangeDraftService reloaded = createService();
        var preview = reloaded.readPreview(scope, draft.draftId(), draft.contentHash(), 10, "").items().getFirst();
        var after = (GraphNode) preview.after();
        assertThat(after.properties()).containsEntry("name", "changed");
        assertThat(((Number) after.properties().get("age")).longValue()).isEqualTo(10);

        assertThat(reloaded.apply(scope, draft.draftId(), draft.contentHash()).committed()).isTrue();
        var change = org.mockito.ArgumentCaptor.forClass(GraphChangeSet.class);
        verify(committer).commit(change.capture());
        assertThat(((Number) change.getValue().nodes().getFirst().properties().get("age")).longValue())
                .isEqualTo(10);
    }

    @Test void persistsImmutableVersionsAndPreservesOriginalBaselineDuringMerge() throws Exception {
        GraphDraftView first = service.prepare(scope, request(null, null,
                "[{\"nodeId\":\"old\",\"labels\":[\"Person\"],\"properties\":{\"name\":\"second\"}}]", "[]", Set.of(), Set.of()));
        GraphDraftView second = createService().prepare(scope, request(first.draftId(), first.contentHash(),
                "[{\"nodeId\":\"old\",\"labels\":[\"Person\"],\"properties\":{\"name\":\"third\",\"age\":null}}]", "[]", Set.of(), Set.of()));
        GraphDraftPreviewItem item = service.readPreview(scope, second.draftId(), second.contentHash(), 10, "").items().getFirst();
        assertThat(((GraphNode) item.before()).properties()).containsEntry("name", "original");
        assertThat(((GraphNode) item.after()).properties()).containsEntry("name", "third").doesNotContainKey("age");
        assertThat(second.rootDraftId()).isEqualTo(first.rootDraftId());
        assertThat(second.draftId()).isNotEqualTo(first.draftId());
        verify(committer, never()).commit(any());
        assertThatThrownBy(() -> service.apply(scope, first.draftId(), first.contentHash())).isInstanceOf(IllegalStateException.class);
        assertThat(createService().read(scope, second.draftId(), second.contentHash()).contentHash()).isEqualTo(second.contentHash());
    }

    @Test void additionsCancelOnDeleteAndDiscardRestoresBaseline() throws Exception {
        GraphDraftView first = service.prepare(scope, request(null, null,
                "[{\"nodeId\":\"new\",\"labels\":[\"Person\"],\"properties\":{\"name\":\"new\"}},{\"nodeId\":\"old\",\"labels\":[\"Person\"],\"properties\":{\"name\":\"second\"}}]", "[]", Set.of(), Set.of()));
        GraphDraftView second = service.prepare(scope, request(first.draftId(), first.contentHash(), "[]", "[]", Set.of("new"), Set.of("node:old")));
        assertThat(second.nodeCount()).isZero();
        assertThat(second.deleteNodeCount()).isZero();
        assertThat(service.readPreview(scope, second.draftId(), second.contentHash(), 10, "").items()).isEmpty();
        assertThatThrownBy(() -> service.apply(scope, second.draftId(), second.contentHash())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsMissingEndpointsScopeWideningAndChangedBaseline() throws Exception {
        assertThatThrownBy(() -> service.prepare(scope, request(null, null, "[]",
                "[{\"relationId\":\"r\",\"sourceNodeId\":\"old\",\"targetNodeId\":\"missing\",\"relationType\":\"KNOWS\",\"properties\":{}}]", Set.of(), Set.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("endpoint");
        GraphDraftScope restricted = new GraphDraftScope("000000", "user", "session", "run", "trace", null,
                new com.harness.core.model.GraphRequestContext("other", "schema", Set.of(), Set.of()));
        assertThatThrownBy(() -> service.prepare(restricted, request(null, null, "[]", "[]", Set.of("old"), Set.of())))
                .isInstanceOf(SecurityException.class);
        GraphDraftView first = service.prepare(scope, request(null, null, "[]", "[]", Set.of("old"), Set.of()));
        doReturn(new GraphNode("old", Set.of("Person"), Map.of("name", "external"))).when(graphStore).getNode(any());
        assertThatThrownBy(() -> service.readPreview(scope, first.draftId(), first.contentHash(), 10, ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("baseline");
    }

    @Test void ownershipHashAndWritePermissionAreCheckedAndApplyIsIdempotent() throws Exception {
        GraphDraftView first = service.prepare(scope, request(null, null, "[]", "[]", Set.of("old"), Set.of()));
        assertThatThrownBy(() -> service.read(new GraphDraftScope("000000", "other", "session", null, null, null, null), first.draftId(), first.contentHash()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.apply(scope, first.draftId(), "wrong")).isInstanceOf(IllegalStateException.class);
        doThrow(new SecurityException("read only")).when(access).requireWritable(any(), anyString(), anyString());
        assertThatThrownBy(() -> service.apply(scope, first.draftId(), first.contentHash())).isInstanceOf(SecurityException.class);
        doNothing().when(access).requireWritable(any(), anyString(), anyString());
        assertThat(service.apply(scope, first.draftId(), first.contentHash()).committed()).isTrue();
        assertThat(service.apply(scope, first.draftId(), first.contentHash()).committed()).isTrue();
        verify(committer, times(1)).commit(any());
    }

    @Test void permanentFailureIsReconciledAcrossRestartAndCanBecomeANewUncommittedVersion() throws Exception {
        GraphDraftView draft = service.prepare(scope, request(null, null, "[]", "[]", Set.of("old"), Set.of()));
        doThrow(new IllegalStateException("permanent graph failure")).when(committer).commit(any());
        assertThatThrownBy(() -> service.apply(scope, draft.draftId(), draft.contentHash())).hasMessage("permanent graph failure");
        when(committer.findFailure(draft.requestId())).thenReturn(Optional.of(new GraphMutationCommitter.Failure("baseline conflict", false)));
        GraphChangeDraftService reloaded = createService();
        GraphDraftView failed = reloaded.read(scope, draft.draftId(), draft.contentHash());
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.failure().message()).isEqualTo("baseline conflict");
        assertThatThrownBy(() -> reloaded.apply(scope, draft.draftId(), draft.contentHash())).hasMessage("baseline conflict");
        verify(committer, times(1)).commit(any());
        GraphDraftView next = reloaded.prepare(scope, request(draft.draftId(), draft.contentHash(), "[]", "[]", Set.of(), Set.of("node:old")));
        assertThat(next.status()).isEqualTo("PENDING");
        assertThat(next.requestId()).isNotEqualTo(draft.requestId());
        assertThat(next.failure()).isNull();
    }

    @Test void permanentKnowledgeFailurePreservesGraphCommitAndCannotBeEditedOrReapplied() throws Exception {
        GraphDraftView draft = service.prepare(scope, request(null, null, "[]", "[]", Set.of("old"), Set.of()));
        doThrow(new IllegalStateException("knowledge failed")).when(committer).commit(any());
        when(committer.findFailure(draft.requestId())).thenReturn(Optional.of(new GraphMutationCommitter.Failure("knowledge failed", true)));
        assertThatThrownBy(() -> service.apply(scope, draft.draftId(), draft.contentHash())).hasMessage("knowledge failed");
        GraphDraftView failed = createService().read(scope, draft.draftId(), draft.contentHash());
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.failure().graphCommitted()).isTrue();
        assertThatThrownBy(() -> service.prepare(scope, request(draft.draftId(), draft.contentHash(), "[]", "[]", Set.of(), Set.of())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot be edited");
        assertThatThrownBy(() -> service.apply(scope, draft.draftId(), draft.contentHash())).hasMessage("knowledge failed");
        verify(committer, times(1)).commit(any());
    }

    @Test void previewPaginationIsBoundToHashAndContinuationCannotSwitchTargetOrSession() throws Exception {
        GraphDraftView first = service.prepare(scope, request(null, null,
                "[{\"nodeId\":\"old\",\"properties\":{\"name\":\"second\"}},{\"nodeId\":\"new\",\"labels\":[\"Person\"],\"properties\":{\"name\":\"new\"}}]", "[]", Set.of(), Set.of()));
        var page = service.readPreview(scope, first.draftId(), first.contentHash(), 1, "");
        assertThat(page.pageInfo().hasMore()).isTrue();
        assertThat(service.readPreview(scope, first.draftId(), first.contentHash(), 1, page.pageInfo().nextCursor()).items())
                .extracting(GraphDraftPreviewItem::changeId).doesNotContain(page.items().getFirst().changeId());
        GraphDraftPrepareRequest wrongTarget = new GraphDraftPrepareRequest("other", "schema", first.draftId(), first.contentHash(), mapper.createArrayNode(), mapper.createArrayNode(), Set.of(), Set.of(), Set.of());
        assertThatThrownBy(() -> service.prepare(scope, wrongTarget)).isInstanceOf(SecurityException.class);
        GraphDraftScope wrongSession = new GraphDraftScope("000000", "user", "different-session", "run", "trace", null, null);
        assertThatThrownBy(() -> service.read(wrongSession, first.draftId(), first.contentHash())).isInstanceOf(SecurityException.class);
        GraphDraftView second = service.prepare(scope, request(first.draftId(), first.contentHash(), "[]", "[]", Set.of(), Set.of()));
        assertThat(second.nodeCount()).isEqualTo(2);
        assertThatThrownBy(() -> service.readPreview(scope, second.draftId(), second.contentHash(), 1, page.pageInfo().nextCursor()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cursor");
        assertThatThrownBy(() -> service.prepare(scope, request(first.draftId(), first.contentHash(), "[]", "[]", Set.of(), Set.of())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("current");
    }

    @Test void applicationFreezeAndDurableCompletionRecoverAcrossRestart() throws Exception {
        GraphDraftView draft = service.prepare(scope, request(null, null, "[]", "[]", Set.of("old"), Set.of()));
        doAnswer(invocation -> {
            doReturn(null).when(graphStore).getNode(any());
            throw new IllegalStateException("projection pending");
        }).when(committer).commit(any());
        assertThatThrownBy(() -> service.apply(scope, draft.draftId(), draft.contentHash())).hasMessageContaining("projection pending");
        assertThat(createService().read(scope, draft.draftId(), draft.contentHash()).status()).isEqualTo("APPLYING");
        var preview = createService().readPreview(scope, draft.draftId(), draft.contentHash(), 10, "").items().getFirst();
        assertThat(((GraphNode) preview.before()).properties()).containsEntry("name", "original");
        assertThat(preview.after()).isNull();
        assertThatThrownBy(() -> createService().prepare(scope, request(draft.draftId(), draft.contentHash(), "[]", "[]", Set.of(), Set.of())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("edited");
        when(committer.findCommitted(draft.requestId())).thenReturn(Optional.of(new GraphMutationResult(draft.requestId(), true, 0, 0)));
        assertThat(createService().read(scope, draft.draftId(), draft.contentHash()).status()).isEqualTo("APPLIED");
        assertThat(createService().apply(scope, draft.draftId(), draft.contentHash()).committed()).isTrue();
        verify(committer, times(1)).commit(any());
    }

    @Test void duplicateAndContradictoryPatchesDoNotPublishArtifacts() throws Exception {
        assertThatThrownBy(() -> service.prepare(scope, request(null, null,
                "[{\"nodeId\":\"old\",\"properties\":{\"name\":\"x\"}},{\"nodeId\":\"old\",\"properties\":{\"name\":\"y\"}}]", "[]", Set.of(), Set.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Contradictory");
        assertThatThrownBy(() -> service.prepare(scope, request(null, null,
                "[{\"nodeId\":\"old\",\"properties\":{\"name\":\"x\"}}]", "[]", Set.of("old"), Set.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Contradictory");
        try (var files = java.nio.file.Files.list(directory)) { assertThat(files.count()).isZero(); }
    }

    @Test void sensitiveBaselineIsInternalAndReloadsWithoutPublicArtifactMetadata() throws Exception {
        schemas.replace(new GraphSchemaDefinition("schema", 1, GraphSchemaMode.STRICT,
                Map.of("Person", new GraphNodeTypeDefinition("Person", Map.of(
                        "name", new GraphPropertyDefinition("name", GraphPropertyType.STRING, true, false, true, true),
                        "age", new GraphPropertyDefinition("age", GraphPropertyType.INTEGER, false, true, true, true)))),
                Map.of("KNOWS", new GraphRelationTypeDefinition("KNOWS", Set.of("Person"), Set.of("Person"), Map.of())), 1, 2));
        GraphDraftView draft = service.prepare(scope, request(null, null,
                "[{\"nodeId\":\"old\",\"properties\":{\"name\":\"changed\"}}]", "[]", Set.of(), Set.of()));
        var publicStore = new FilesystemArtifactStore(directory);
        assertThat(publicStore.listBySession(scope.sessionId())).isEmpty();
        assertThat(publicStore.get(draft.draftId())).isEmpty();
        artifacts = new ArtifactStorageService(publicStore, directory, 5);
        var reloaded = createService();
        assertThat(reloaded.read(scope, draft.draftId(), draft.contentHash()).contentHash()).isEqualTo(draft.contentHash());
        var preview = reloaded.readPreview(scope, draft.draftId(), draft.contentHash(), 10, "").items().getFirst();
        assertThat(((GraphNode) preview.before()).properties()).doesNotContainKey("age");
        assertThat(((GraphNode) preview.after()).properties()).doesNotContainKey("age");
        assertThat(mapper.readTree(artifacts.readGraphDraftPayload(draft.draftId()))
                .path("baseline").path("nodes").path("old").path("properties").path("age").asInt()).isEqualTo(10);
    }

    @Test void relationUpdatesAndStoredBaselinesRequireOldAndNewSubjectEndpoints() throws Exception {
        GraphRelation original = new GraphRelation("r", "outside-a", "outside-b", "KNOWS", Map.of());
        when(graphStore.getRelation("graph", "schema", "r")).thenReturn(original);
        doAnswer(invocation -> {
            GraphNodeKey key = invocation.getArgument(0);
            return new GraphNode(key.nodeId(), Set.of("Person"), Map.of("name", key.nodeId()));
        }).when(graphStore).getNode(any());
        GraphDraftScope restricted = new GraphDraftScope("000000", "user", "session", "run", "trace", null,
                new com.harness.core.model.GraphRequestContext("graph", "schema", Set.of("a", "b"), Set.of()));
        var delta = request(null, null, "[]", "[{\"relationId\":\"r\",\"sourceNodeId\":\"a\",\"targetNodeId\":\"b\"}]", Set.of(), Set.of());
        assertThatThrownBy(() -> service.prepare(restricted, delta)).isInstanceOf(SecurityException.class).hasMessageContaining("subject scope");
        GraphDraftView draft = service.prepare(scope, delta);
        assertThatThrownBy(() -> createService().readPreview(restricted, draft.draftId(), draft.contentHash(), 10, ""))
                .isInstanceOf(SecurityException.class).hasMessageContaining("subject scope");
        assertThatThrownBy(() -> createService().apply(restricted, draft.draftId(), draft.contentHash()))
                .isInstanceOf(SecurityException.class).hasMessageContaining("subject scope");
        verify(committer, never()).commit(any());
    }

    @Test void deletedNodesAndRelationsRequireExplicitDiscardBeforePatch() throws Exception {
        GraphRelation relation = new GraphRelation("r", "old", "old", "KNOWS", Map.of());
        when(graphStore.getRelation("graph", "schema", "r")).thenReturn(relation);
        when(graphStore.listIncidentRelations(anyString(), anyString(), anySet(), anyInt(), anyString()))
                .thenReturn(new PageResponse<>(List.of(relation), new PageInfo(50, "", false)));
        GraphDraftView deletedNode = service.prepare(scope, request(null, null, "[]", "[]", Set.of("old"), Set.of()));
        assertThatThrownBy(() -> service.prepare(scope, request(deletedNode.draftId(), deletedNode.contentHash(),
                "[{\"nodeId\":\"old\",\"properties\":{\"name\":\"changed\"}}]", "[]", Set.of(), Set.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("discard");
        GraphDraftView restored = service.prepare(scope, request(deletedNode.draftId(), deletedNode.contentHash(),
                "[{\"nodeId\":\"old\",\"properties\":{\"name\":\"changed\"}}]", "[]", Set.of(), Set.of("node:old")));
        assertThat(restored.deleteNodeCount()).isZero();
        assertThat(restored.deleteRelationCount()).isZero();
        GraphDraftView deletedRelation = service.prepare(scope, new GraphDraftPrepareRequest("graph", "schema", null, null,
                mapper.createArrayNode(), mapper.createArrayNode(), Set.of(), Set.of("r"), Set.of()));
        assertThatThrownBy(() -> service.prepare(scope, request(deletedRelation.draftId(), deletedRelation.contentHash(),
                "[]", "[{\"relationId\":\"r\"}]", Set.of(), Set.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("discard");
        GraphDraftView restoredRelation = service.prepare(scope, request(deletedRelation.draftId(), deletedRelation.contentHash(),
                "[]", "[{\"relationId\":\"r\"}]", Set.of(), Set.of("relation:r")));
        assertThat(restoredRelation.deleteRelationCount()).isZero();
        assertThat(restoredRelation.relationCount()).isOne();
    }

    @Test void staleReferencesExposeOnlyAuthorizedCurrentDraftAndNeverApplyOldVersion() throws Exception {
        GraphDraftView first = service.prepare(scope, request(null, null,
                "[{\"nodeId\":\"old\",\"properties\":{\"name\":\"changed\"}}]", "[]", Set.of(), Set.of()));
        GraphDraftView second = service.prepare(scope, request(first.draftId(), first.contentHash(), "[]", "[]", Set.of(), Set.of()));
        assertThatThrownBy(() -> service.read(scope, first.draftId(), first.contentHash()))
                .isInstanceOfSatisfying(GraphDraftConflictException.class, conflict -> {
                    assertThat(conflict.currentDraftId()).isEqualTo(second.draftId());
                    assertThat(conflict.currentContentHash()).isEqualTo(second.contentHash());
                });
        assertThatThrownBy(() -> service.apply(scope, first.draftId(), first.contentHash())).isInstanceOf(GraphDraftConflictException.class);
        assertThatThrownBy(() -> service.read(new GraphDraftScope("000000", "other", "session", null, null, null, null), first.draftId(), first.contentHash()))
                .isInstanceOf(SecurityException.class);
        doThrow(new SecurityException("access revoked")).when(access).requireReadable(any(), anyString(), anyString());
        assertThatThrownBy(() -> service.read(scope, first.draftId(), first.contentHash())).isInstanceOf(SecurityException.class);
        verify(committer, never()).commit(any());
    }

    @Test void staleReferenceDoesNotRevealCurrentDraftOutsideTheTrustedSubjectScope() throws Exception {
        GraphDraftView first = service.prepare(scope, request(null, null,
                "[{\"nodeId\":\"old\",\"properties\":{\"name\":\"changed\"}}]", "[]", Set.of(), Set.of()));
        service.prepare(scope, request(first.draftId(), first.contentHash(),
                "[{\"nodeId\":\"outside\",\"labels\":[\"Person\"],\"properties\":{\"name\":\"outside\"}}]", "[]", Set.of(), Set.of()));
        GraphDraftScope restricted = new GraphDraftScope("000000", "user", "session", "run", "trace", null,
                new com.harness.core.model.GraphRequestContext("graph", "schema", Set.of("old"), Set.of()));
        assertThatThrownBy(() -> service.read(restricted, first.draftId(), first.contentHash()))
                .isInstanceOf(SecurityException.class).hasMessageContaining("subject scope");
    }

    @Test void legacyPublicArtifactReferencesAreRejectedWithoutMigratingOrDeletingFiles() throws Exception {
        GraphDraftView draft = service.prepare(scope, request(null, null, "[]", "[]", Set.of("old"), Set.of()));
        byte[] payload = artifacts.readGraphDraftPayload(draft.draftId());
        var legacy = artifacts.store(payload, "graph-change-draft.json", "application/json", scope.sessionId());
        var reference = mapper.createObjectNode();
        reference.put("rootDraftId", draft.rootDraftId()).put("draftId", draft.draftId()).put("artifactId", legacy.id())
                .put("contentHash", draft.contentHash()).put("sessionId", scope.sessionId()).put("status", "PENDING").putNull("result");
        artifacts.writeGraphDraftReference(draft.draftId(), mapper.writeValueAsBytes(reference));
        assertThatThrownBy(() -> service.read(scope, draft.draftId(), draft.contentHash()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("persisted graph draft");
        assertThat(new FilesystemArtifactStore(directory).get(legacy.id())).isPresent();
        assertThat(java.nio.file.Files.readAllBytes(Path.of(legacy.filePath()))).isEqualTo(payload);
    }
}

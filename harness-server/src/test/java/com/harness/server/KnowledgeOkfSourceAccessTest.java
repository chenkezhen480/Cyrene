package com.harness.server;

import com.harness.agent.graph.GraphSpaceAccessService;
import com.harness.core.knowledge.KnowledgeConcept;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeNamespaceType;
import com.harness.core.knowledge.KnowledgeSource;
import com.harness.core.knowledge.KnowledgeSourceType;
import com.harness.core.knowledge.KnowledgeStatus;
import com.harness.core.model.MemoryMessage;
import com.harness.core.model.Session;
import com.harness.input.memory.MessageStore;
import com.harness.input.memory.SessionStore;
import com.harness.graph.schema.GraphSchemaDefinition;
import com.harness.graph.schema.GraphSchemaRegistry;
import com.harness.tool.knowledge.authority.KnowledgeArtifactRepository;
import com.harness.tool.knowledge.authority.KnowledgeRepository;
import com.harness.tool.knowledge.okf.OkfBundleScope;
import com.harness.trace.store.TraceStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeOkfSourceAccessTest {

    private static final Instant NOW = Instant.parse("2026-09-03T03:00:00Z");

    @Test
    void sessionMessageMustExistInTheAuthorizedSession() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KnowledgeArtifactRepository artifactRepository = mock(KnowledgeArtifactRepository.class);
        SessionStore sessionStore = mock(SessionStore.class);
        MessageStore messageStore = mock(MessageStore.class);
        TraceStore traceStore = mock(TraceStore.class);
        KnowledgeOkfSourceAccess access = new KnowledgeOkfSourceAccess(
                repository, artifactRepository, sessionStore, messageStore, traceStore,
                mock(GraphSpaceAccessService.class), mock(GraphSchemaRegistry.class));
        OkfBundleScope scope = OkfBundleScope.user("tenant-a", "user-1");
        KnowledgeConcept concept = new KnowledgeConcept(
                "concept-1", "tenant-a", "user-1", KnowledgeNamespaceType.USER_MEMORY,
                null, KnowledgeConceptType.USER_EPISODE, null, KnowledgeStatus.DRAFT,
                "revision-1", 1, null, NOW, NOW);
        KnowledgeSource source = new KnowledgeSource(
                "revision-1", KnowledgeSourceType.SESSION_MESSAGE, "message-17",
                "cyrene://session/session-1/message/17", NOW, NOW);

        when(sessionStore.findByIdAndOwner("session-1", "user-1", "tenant-a"))
                .thenReturn(Optional.of(mock(Session.class)));
        when(messageStore.findByIdAndSession(17L, "session-1"))
                .thenReturn(Optional.empty());
        assertThat(access.canExport(scope, concept, source)).isFalse();

        when(messageStore.findByIdAndSession(17L, "session-1"))
                .thenReturn(Optional.of(mock(MemoryMessage.class)));
        assertThat(access.canExport(scope, concept, source)).isTrue();
    }

    @Test
    void malformedOrCrossOwnerMessageResourceIsRejected() {
        SessionStore sessionStore = mock(SessionStore.class);
        MessageStore messageStore = mock(MessageStore.class);
        KnowledgeOkfSourceAccess access = new KnowledgeOkfSourceAccess(
                mock(KnowledgeRepository.class), mock(KnowledgeArtifactRepository.class),
                sessionStore, messageStore, mock(TraceStore.class),
                mock(GraphSpaceAccessService.class),
                mock(GraphSchemaRegistry.class));
        OkfBundleScope scope = OkfBundleScope.user("tenant-a", "user-1");
        KnowledgeConcept concept = new KnowledgeConcept(
                "concept-1", "tenant-a", "user-1", KnowledgeNamespaceType.USER_MEMORY,
                null, KnowledgeConceptType.USER_EPISODE, null, KnowledgeStatus.DRAFT,
                "revision-1", 1, null, NOW, NOW);

        when(sessionStore.findByIdAndOwner("session-2", "user-1", "tenant-a"))
                .thenReturn(Optional.empty());
        assertThat(access.canExport(scope, concept, source("session-2", "17"))).isFalse();
        assertThat(access.canExport(scope, concept, source("session-1", "not-a-number"))).isFalse();
    }

    @Test
    void graphResourcesRequireTheExactReadableGraphAndRegisteredSchema() {
        GraphSpaceAccessService graphAccessService = mock(GraphSpaceAccessService.class);
        GraphSchemaRegistry schemaRegistry = mock(GraphSchemaRegistry.class);
        KnowledgeOkfSourceAccess access = new KnowledgeOkfSourceAccess(
                mock(KnowledgeRepository.class), mock(KnowledgeArtifactRepository.class),
                mock(SessionStore.class), mock(MessageStore.class), mock(TraceStore.class),
                graphAccessService, schemaRegistry);
        KnowledgeConcept schemaConcept = new KnowledgeConcept(
                "concept-schema", null, null, KnowledgeNamespaceType.GRAPH,
                "schema-1", KnowledgeConceptType.GRAPH_SCHEMA, "schema-1",
                KnowledgeStatus.STABLE, "revision-schema", 1, null, NOW, NOW);

        when(schemaRegistry.find("schema-1"))
                .thenReturn(Optional.of(mock(GraphSchemaDefinition.class)));
        assertThat(access.canExportResource(
                OkfBundleScope.graph("tenant-a", "graph-1", "schema-1"),
                schemaConcept, "cyrene://graph-schemas/schema-1")).isTrue();
        assertThat(access.canExportResource(
                OkfBundleScope.graph("tenant-a", "graph-1", "schema-1"),
                schemaConcept, "cyrene://graphs/graph-1")).isTrue();
        assertThat(access.canExportResource(
                OkfBundleScope.graph("tenant-a", "graph-1", "schema-1"),
                schemaConcept, "cyrene://graph-schemas/schema-2")).isFalse();
    }

    private static KnowledgeSource source(String sessionId, String messageId) {
        return new KnowledgeSource(
                "revision-1", KnowledgeSourceType.SESSION_MESSAGE, "message-" + messageId,
                "cyrene://session/" + sessionId + "/message/" + messageId, NOW, NOW);
    }

}

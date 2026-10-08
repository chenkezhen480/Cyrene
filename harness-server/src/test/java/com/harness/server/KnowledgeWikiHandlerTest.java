package com.harness.server;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.harness.agent.graph.GraphSpaceAccessService;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.knowledge.KnowledgeConcept;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeNamespaceType;
import com.harness.core.knowledge.KnowledgeRevision;
import com.harness.core.knowledge.KnowledgeStatus;
import com.harness.core.model.PageInfo;
import com.harness.core.model.PageResponse;
import com.harness.core.security.RequestPrincipal;
import com.harness.server.security.RequestPrincipalResolver;
import com.harness.server.api.ApiError;
import com.harness.server.api.ApiErrorCode;
import com.harness.graph.store.KnowledgeGraphStore;
import com.harness.tool.knowledge.KnowledgeWikiService;
import com.harness.tool.knowledge.authority.KnowledgeHead;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class KnowledgeWikiHandlerTest {

    @Test
    void mutationsKeepGlobalKnowledgeInTheManagementTenant() {
        EnvConfig.init(Map.of(EnvKey.AUTH_MODE, "none", EnvKey.INTERNAL_API_ADMIN_TENANT_ID, "management"));
        for (boolean update : List.of(true, false)) {
            var ordinary = mutationPredicate(update, "tenant-a");
            assertThat(ordinary).rejects(knowledgeHead(KnowledgeConceptType.SOURCE_DOCUMENT, null, null));
            assertThat(ordinary).rejects(knowledgeHead(KnowledgeConceptType.OPERATION_PLAYBOOK, null, null));
            assertThat(ordinary).accepts(knowledgeHead(KnowledgeConceptType.USER_EPISODE, "tenant-a", "user-a"));
            assertThat(ordinary).accepts(knowledgeHead(KnowledgeConceptType.OPERATION_PLAYBOOK, "tenant-a", null));
            assertThat(ordinary).rejects(knowledgeHead(KnowledgeConceptType.USER_EPISODE, "tenant-b", "user-a"));
            assertThat(ordinary).rejects(knowledgeHead(KnowledgeConceptType.USER_EPISODE, "tenant-a", "other-user"));
            var management = mutationPredicate(update, "management");
            assertThat(management).accepts(knowledgeHead(KnowledgeConceptType.SOURCE_DOCUMENT, null, null));
            assertThat(management).accepts(knowledgeHead(KnowledgeConceptType.OPERATION_PLAYBOOK, null, null));
        }
    }

    @SuppressWarnings("unchecked")
    private static Predicate<KnowledgeHead> mutationPredicate(boolean update, String tenantId) {
        var service = mock(KnowledgeWikiService.class);
        var context = mock(Context.class);
        when(context.attribute(RequestPrincipalResolver.PRINCIPAL_ATTRIBUTE)).thenReturn(new RequestPrincipal(
                "user-a", tenantId, "editor", RequestPrincipal.AuthenticationType.JWT));
        when(context.pathParam("conceptId")).thenReturn("concept-1");
        when(context.queryParam("revisionId")).thenReturn("revision-1");
        when(context.bodyAsClass(KnowledgeWikiHandler.WikiUpdate.class))
                .thenReturn(new KnowledgeWikiHandler.WikiUpdate("revision-1", "title", "summary"));
        var handler = new KnowledgeWikiHandler(service, mock(GraphSpaceAccessService.class), mock(KnowledgeGraphStore.class));
        ArgumentCaptor<Predicate<KnowledgeHead>> predicate = ArgumentCaptor.forClass(Predicate.class);
        if (update) {
            handler.update(context);
            verify(service).update(eq("concept-1"), eq("revision-1"), eq("title"), eq("summary"), eq("user-a"), predicate.capture());
        } else {
            handler.delete(context);
            verify(service).delete(eq("concept-1"), eq("revision-1"), predicate.capture());
        }
        return predicate.getValue();
    }

    private static KnowledgeHead knowledgeHead(KnowledgeConceptType type, String tenantId, String userId) {
        Instant now = Instant.EPOCH;
        var concept = new KnowledgeConcept("concept-1", tenantId, userId, KnowledgeNamespaceType.OPERATION_MEMORY,
                "namespace", type, "key", KnowledgeStatus.STABLE, "revision-1", 1, null, now, now);
        var revision = new KnowledgeRevision("revision-1", "concept-1", 1, "title", "summary", "body",
                "test", now, "hash", Map.of(), now);
        return new KnowledgeHead(concept, revision);
    }

    @Test
    void listDefaultsToFiveCards() {
        EnvConfig.init(Map.of(EnvKey.AUTH_MODE, "none"));
        var service = mock(KnowledgeWikiService.class);
        var context = mock(Context.class);
        when(context.queryParam("userId")).thenReturn("alice");
        when(service.page(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new PageResponse<>(List.of(), new PageInfo(5, "", false)));

        new KnowledgeWikiHandler(service, mock(GraphSpaceAccessService.class),
                mock(KnowledgeGraphStore.class)).list(context);

        verify(service).page(any(), any(), eq(KnowledgeConceptType.SOURCE_DOCUMENT),
                any(), any(), eq(5), any());
    }

    @Test
    void malformedJsonReturnsExplicitBadRequest() {
        EnvConfig.init(Map.of(EnvKey.AUTH_MODE, "none"));
        var service = mock(KnowledgeWikiService.class);
        var context = mock(Context.class);
        when(context.queryParam("userId")).thenReturn("test-user");
        when(context.status(400)).thenReturn(context);
        when(context.json(any())).thenReturn(context);
        // Javalin's Kotlin mapper can propagate Jackson's checked exception.
        doAnswer(call -> { throw new JsonParseException((JsonParser) null, "invalid JSON"); })
                .when(context).bodyAsClass(KnowledgeWikiHandler.WikiUpdate.class);
        new KnowledgeWikiHandler(service, mock(GraphSpaceAccessService.class),
                mock(KnowledgeGraphStore.class)).update(context);
        var result = ArgumentCaptor.forClass(Object.class);
        verify(context).json(result.capture());
        assertThat(result.getValue()).isInstanceOf(ApiError.class);
        assertThat(((ApiError) result.getValue()).code()).isEqualTo(ApiErrorCode.INVALID_REQUEST);
        verifyNoInteractions(service);
    }

    @Test
    void globalExportIgnoresUiFiltersAndReturnsOneMarkdownFile() {
        EnvConfig.init(Map.of(EnvKey.AUTH_MODE, "none"));
        var service = mock(KnowledgeWikiService.class);
        var context = mock(Context.class);
        when(context.queryParam("userId")).thenReturn("alice");
        when(service.exportMarkdown()).thenReturn("# LLM Wiki\n\nAll authorized cards");
        new KnowledgeWikiHandler(service, mock(GraphSpaceAccessService.class),
                mock(KnowledgeGraphStore.class)).exportAll(context);
        verify(context).contentType("text/markdown; charset=utf-8");
        verify(context).header("Content-Disposition", "attachment; filename=\"llm-wiki.md\"");
        verify(context).result("# LLM Wiki\n\nAll authorized cards");
        verify(context, never()).queryParam("collection");
        verify(context, never()).queryParam("type");
    }

    /**
     * A Schema that no graph space uses yet must stay manageable, so its Wiki card is readable
     * without consulting the graph-space access list at all.
     */
    @Test
    void schemaCardWithoutAnyGraphSpaceIsReadable() {
        var service = mock(KnowledgeWikiService.class);
        var graphAccess = mock(GraphSpaceAccessService.class);
        var graphStore = mock(KnowledgeGraphStore.class);
        var context = mock(Context.class);

        when(context.queryParam("userId")).thenReturn("alice");
        when(context.pathParam("conceptId")).thenReturn("concept-1");
        when(context.json(any())).thenReturn(context);
        when(service.get(eq("concept-1"), any())).thenReturn(mock(KnowledgeWikiService.WikiCard.class));
        when(graphStore.providerName()).thenReturn("neo4j");
        when(graphStore.hasGraphSpacesForSchema("student-capability-v2")).thenReturn(false);

        new KnowledgeWikiHandler(service, graphAccess, graphStore).get(context);

        assertThat(authorizedPredicate(service))
                .accepts(graphSchemaHead("student-capability-v2"));
        verify(graphAccess, never()).listReadable(any(), anyInt(), any());
    }

    @Test
    void schemaCardStaysGatedWhileAnUnreadableGraphSpaceUsesIt() {
        var service = mock(KnowledgeWikiService.class);
        var graphAccess = mock(GraphSpaceAccessService.class);
        var graphStore = mock(KnowledgeGraphStore.class);
        var context = mock(Context.class);

        when(context.queryParam("userId")).thenReturn("alice");
        when(context.pathParam("conceptId")).thenReturn("concept-1");
        when(context.json(any())).thenReturn(context);
        when(service.get(eq("concept-1"), any())).thenReturn(mock(KnowledgeWikiService.WikiCard.class));
        when(graphStore.providerName()).thenReturn("neo4j");
        when(graphStore.hasGraphSpacesForSchema("student-capability-v2")).thenReturn(true);
        when(graphAccess.listReadable(any(), anyInt(), any()))
                .thenReturn(new PageResponse<>(List.of(), new PageInfo(100, "", false)));

        new KnowledgeWikiHandler(service, graphAccess, graphStore).get(context);

        assertThat(authorizedPredicate(service))
                .rejects(graphSchemaHead("student-capability-v2"));
    }

    @Test
    void schemaCardStaysGatedWhenTheGraphProviderIsDisabled() {
        var service = mock(KnowledgeWikiService.class);
        var graphAccess = mock(GraphSpaceAccessService.class);
        var graphStore = mock(KnowledgeGraphStore.class);
        var context = mock(Context.class);

        when(context.queryParam("userId")).thenReturn("alice");
        when(context.pathParam("conceptId")).thenReturn("concept-1");
        when(context.json(any())).thenReturn(context);
        when(service.get(eq("concept-1"), any())).thenReturn(mock(KnowledgeWikiService.WikiCard.class));
        when(graphStore.providerName()).thenReturn("none");
        when(graphAccess.listReadable(any(), anyInt(), any()))
                .thenReturn(new PageResponse<>(List.of(), new PageInfo(100, "", false)));

        new KnowledgeWikiHandler(service, graphAccess, graphStore).get(context);

        assertThat(authorizedPredicate(service))
                .rejects(graphSchemaHead("student-capability-v2"));
    }

    @SuppressWarnings("unchecked")
    private static Predicate<KnowledgeHead> authorizedPredicate(KnowledgeWikiService service) {
        ArgumentCaptor<Predicate<KnowledgeHead>> captor = ArgumentCaptor.forClass(Predicate.class);
        verify(service).get(eq("concept-1"), captor.capture());
        return captor.getValue();
    }

    private static KnowledgeHead graphSchemaHead(String schemaId) {
        Instant now = Instant.parse("2026-09-17T00:00:00Z");
        var concept = new KnowledgeConcept(
                "concept-1", null, null, KnowledgeNamespaceType.GRAPH, schemaId,
                KnowledgeConceptType.GRAPH_SCHEMA, schemaId, KnowledgeStatus.STABLE,
                "revision-1", 1, null, now, now);
        var revision = new KnowledgeRevision(
                "revision-1", "concept-1", 1, "Graph Schema " + schemaId, "summary", "body",
                "test", now, "hash", Map.of(), now);
        return new KnowledgeHead(concept, revision);
    }
}

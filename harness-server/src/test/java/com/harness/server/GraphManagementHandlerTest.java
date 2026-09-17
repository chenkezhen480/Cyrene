package com.harness.server;

import com.harness.core.model.PageInfo;
import com.harness.core.model.PageResponse;
import com.harness.graph.config.GraphSettings;
import com.harness.graph.model.GraphDeleteMode;
import com.harness.graph.model.GraphDeleteRequest;
import com.harness.graph.model.GraphDeleteResult;
import com.harness.graph.model.GraphDeleteTarget;
import com.harness.graph.model.GraphNode;
import com.harness.graph.model.GraphNodePageRequest;
import com.harness.graph.model.GraphSpacePageRequest;
import com.harness.graph.model.GraphSpaceSummary;
import com.harness.graph.schema.GraphSchemaRegistry;
import com.harness.graph.store.KnowledgeGraphStore;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphManagementHandlerTest {

    @Test
    void forwardsNameFilterWhenListingNodes() {
        KnowledgeGraphStore graphStore = mock(KnowledgeGraphStore.class);
        GraphSchemaRegistry schemaRegistry = mock(GraphSchemaRegistry.class);
        GraphSettings settings = mock(GraphSettings.class);
        GraphRequestAuthenticator authenticator = mock(GraphRequestAuthenticator.class);
        Context context = mock(Context.class);
        PageResponse<GraphNode> page = new PageResponse<>(
                List.of(new GraphNode("student-1", Set.of("Student"), Map.of("name", "小明"))),
                new PageInfo(25, "", false)
        );

        when(context.queryParam("graphId")).thenReturn("students");
        when(context.queryParam("schemaId")).thenReturn("student-v1");
        when(context.queryParam("label")).thenReturn("");
        when(context.queryParam("name")).thenReturn("小明");
        when(context.queryParam("limit")).thenReturn("25");
        when(context.queryParam("cursor")).thenReturn("");
        when(context.json(any())).thenReturn(context);
        when(settings.maxLimit()).thenReturn(200);
        when(graphStore.listNodes(any())).thenReturn(page);

        GraphManagementHandler handler = new GraphManagementHandler(
                graphStore, schemaRegistry, settings, authenticator,
                mock(GraphDeletionService.class));
        handler.listNodes(context);

        verify(authenticator).authenticate(context);
        ArgumentCaptor<GraphNodePageRequest> requestCaptor =
                ArgumentCaptor.forClass(GraphNodePageRequest.class);
        verify(graphStore).listNodes(requestCaptor.capture());
        assertThat(requestCaptor.getValue()).isEqualTo(new GraphNodePageRequest(
                "students", "student-v1", "", "小明", 25, ""));
        verify(context).json(page);
    }

    @Test
    void authenticatesAndListsGraphSpacesWithCursorPagination() {
        KnowledgeGraphStore graphStore = mock(KnowledgeGraphStore.class);
        GraphSchemaRegistry schemaRegistry = mock(GraphSchemaRegistry.class);
        GraphSettings settings = mock(GraphSettings.class);
        GraphRequestAuthenticator authenticator = mock(GraphRequestAuthenticator.class);
        Context context = mock(Context.class);
        PageResponse<GraphSpaceSummary> page = new PageResponse<>(
                List.of(new GraphSpaceSummary("graph-1", "schema-v1", 3, 2)),
                new PageInfo(10, "", false)
        );

        when(context.queryParam("limit")).thenReturn("10");
        when(context.queryParam("cursor")).thenReturn("opaque-cursor");
        when(context.json(any())).thenReturn(context);
        when(settings.maxLimit()).thenReturn(200);
        when(graphStore.listGraphSpaces(any())).thenReturn(page);

        GraphManagementHandler handler = new GraphManagementHandler(
                graphStore, schemaRegistry, settings, authenticator,
                mock(GraphDeletionService.class));
        handler.listGraphSpaces(context);

        verify(authenticator).authenticate(context);
        ArgumentCaptor<GraphSpacePageRequest> requestCaptor =
                ArgumentCaptor.forClass(GraphSpacePageRequest.class);
        verify(graphStore).listGraphSpaces(requestCaptor.capture());
        assertThat(requestCaptor.getValue())
                .isEqualTo(new GraphSpacePageRequest(10, "opaque-cursor"));
        verify(context).json(page);
    }

    @Test
    void deletesGraphSpaceThroughTheCascadeAndReturnsItsCounts() {
        GraphSettings settings = mock(GraphSettings.class);
        GraphRequestAuthenticator authenticator = mock(GraphRequestAuthenticator.class);
        GraphDeletionService deletionService = mock(GraphDeletionService.class);
        Context context = mock(Context.class);
        GraphDeletionService.GraphSpaceDeletionResult result =
                new GraphDeletionService.GraphSpaceDeletionResult("students", "student-v1", 2, 1, 3);

        when(context.queryParam("graphId")).thenReturn("students");
        when(context.queryParam("schemaId")).thenReturn("student-v1");
        when(context.json(any())).thenReturn(context);
        when(deletionService.deleteSpace("students", "student-v1")).thenReturn(result);

        GraphManagementHandler handler = new GraphManagementHandler(
                mock(KnowledgeGraphStore.class), mock(GraphSchemaRegistry.class), settings,
                authenticator, deletionService);
        handler.deleteGraphSpace(context);

        verify(authenticator).authenticate(context);
        verify(deletionService).deleteSpace("students", "student-v1");
        verify(context).json(result);
    }

    @Test
    void deletesDetachedNodeWithTheModeTheConsoleSends() {
        KnowledgeGraphStore graphStore = mock(KnowledgeGraphStore.class);
        GraphRequestAuthenticator authenticator = mock(GraphRequestAuthenticator.class);
        Context context = mock(Context.class);

        when(context.pathParam("nodeId")).thenReturn("student-1");
        when(context.queryParam("graphId")).thenReturn("students");
        when(context.queryParam("schemaId")).thenReturn("student-v1");
        when(context.queryParam("mode")).thenReturn("DETACH");
        when(context.json(any())).thenReturn(context);
        when(graphStore.delete(any())).thenReturn(new GraphDeleteResult(1, 2));

        GraphManagementHandler handler = new GraphManagementHandler(
                graphStore, mock(GraphSchemaRegistry.class), mock(GraphSettings.class),
                authenticator, mock(GraphDeletionService.class));
        handler.deleteNode(context);

        ArgumentCaptor<GraphDeleteRequest> requestCaptor =
                ArgumentCaptor.forClass(GraphDeleteRequest.class);
        verify(graphStore).delete(requestCaptor.capture());
        assertThat(requestCaptor.getValue()).isEqualTo(new GraphDeleteRequest(
                "students", "student-v1", GraphDeleteTarget.NODE, "student-1",
                GraphDeleteMode.DETACH));
        verify(context).json(new GraphDeleteResult(1, 2));
    }

    @Test
    void defaultsSourceDeletionToDerivedRowsOnly() {
        KnowledgeGraphStore graphStore = mock(KnowledgeGraphStore.class);
        Context context = mock(Context.class);

        when(context.pathParam("sourceId")).thenReturn("doc-7");
        when(context.queryParam("graphId")).thenReturn("students");
        when(context.queryParam("schemaId")).thenReturn("student-v1");
        when(context.json(any())).thenReturn(context);
        when(graphStore.delete(any())).thenReturn(new GraphDeleteResult(0, 4));

        GraphManagementHandler handler = new GraphManagementHandler(
                graphStore, mock(GraphSchemaRegistry.class), mock(GraphSettings.class),
                mock(GraphRequestAuthenticator.class), mock(GraphDeletionService.class));
        handler.deleteSource(context);

        ArgumentCaptor<GraphDeleteRequest> requestCaptor =
                ArgumentCaptor.forClass(GraphDeleteRequest.class);
        verify(graphStore).delete(requestCaptor.capture());
        assertThat(requestCaptor.getValue().mode())
                .isEqualTo(GraphDeleteMode.DELETE_DERIVED_ONLY);
        assertThat(requestCaptor.getValue().target()).isEqualTo(GraphDeleteTarget.SOURCE);
    }

    @Test
    void refusesSingleItemDeletionWhenTheGraphProviderIsDisabled() {
        KnowledgeGraphStore graphStore = mock(KnowledgeGraphStore.class);
        Context context = mock(Context.class);

        when(graphStore.providerName()).thenReturn("none");
        when(context.status(409)).thenReturn(context);
        when(context.json(any())).thenReturn(context);

        GraphManagementHandler handler = new GraphManagementHandler(
                graphStore, mock(GraphSchemaRegistry.class), mock(GraphSettings.class),
                mock(GraphRequestAuthenticator.class), mock(GraphDeletionService.class));
        handler.deleteNode(context);

        verify(graphStore, org.mockito.Mockito.never()).delete(any());
        verify(context).status(409);
    }

    @Test
    void refusesDerivedOnlyModeForNodeDeletion() {
        KnowledgeGraphStore graphStore = mock(KnowledgeGraphStore.class);
        Context context = mock(Context.class);

        when(context.pathParam("nodeId")).thenReturn("student-1");
        when(context.queryParam("graphId")).thenReturn("students");
        when(context.queryParam("schemaId")).thenReturn("student-v1");
        when(context.queryParam("mode")).thenReturn("DELETE_DERIVED_ONLY");
        when(context.status(400)).thenReturn(context);
        when(context.json(any())).thenReturn(context);

        GraphManagementHandler handler = new GraphManagementHandler(
                graphStore, mock(GraphSchemaRegistry.class), mock(GraphSettings.class),
                mock(GraphRequestAuthenticator.class), mock(GraphDeletionService.class));
        handler.deleteNode(context);

        verify(graphStore, org.mockito.Mockito.never()).delete(any());
        verify(context).status(400);
    }
}

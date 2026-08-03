package com.harness.server;

import com.harness.agent.graph.GraphSpaceAccessService;
import com.harness.core.model.PageInfo;
import com.harness.core.model.PageResponse;
import com.harness.graph.config.GraphSettings;
import com.harness.graph.model.GraphNode;
import com.harness.graph.model.GraphDeleteResult;
import com.harness.graph.model.GraphNodePageRequest;
import com.harness.graph.model.GraphSpacePageRequest;
import com.harness.graph.model.GraphSpaceSummary;
import com.harness.graph.model.GraphSpaceKey;
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
                graphStore, schemaRegistry, settings, authenticator);
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
                graphStore, schemaRegistry, settings, authenticator);
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
    void deletesWholeGraphSpaceAndItsAccessBindings() {
        KnowledgeGraphStore graphStore = mock(KnowledgeGraphStore.class);
        GraphSchemaRegistry schemaRegistry = mock(GraphSchemaRegistry.class);
        GraphSettings settings = mock(GraphSettings.class);
        GraphSpaceAccessService accessService = mock(GraphSpaceAccessService.class);
        GraphRequestAuthenticator authenticator = mock(GraphRequestAuthenticator.class);
        Context context = mock(Context.class);

        when(context.queryParam("graphId")).thenReturn("students");
        when(context.queryParam("schemaId")).thenReturn("student-v1");
        when(context.json(any())).thenReturn(context);
        when(graphStore.deleteGraphSpace(new GraphSpaceKey("students", "student-v1")))
                .thenReturn(new GraphDeleteResult(3, 2));
        when(accessService.deleteBindings("students", "student-v1")).thenReturn(1);

        GraphManagementHandler handler = new GraphManagementHandler(
                graphStore, schemaRegistry, settings, accessService, authenticator);
        handler.deleteGraphSpace(context);

        verify(graphStore).deleteGraphSpace(new GraphSpaceKey("students", "student-v1"));
        verify(accessService).deleteBindings("students", "student-v1");
        ArgumentCaptor<Object> responseCaptor = ArgumentCaptor.forClass(Object.class);
        verify(context).json(responseCaptor.capture());
        assertThat(responseCaptor.getValue()).isEqualTo(
                new GraphManagementHandler.GraphSpaceDeleteResponse(
                        "students", "student-v1", 3, 2, 1));
    }
}

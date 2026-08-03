package com.harness.server;

import com.harness.core.model.PageResponse;
import com.harness.graph.config.GraphSettings;
import com.harness.graph.schema.GraphSchemaFormat;
import com.harness.graph.schema.GraphSchemaManagementService;
import com.harness.graph.schema.GraphSchemaMode;
import com.harness.graph.schema.GraphSchemaSource;
import com.harness.graph.schema.GraphSchemaSummary;
import com.harness.graph.store.KnowledgeGraphStore;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphSchemaManagementHandlerTest {

    @Test
    void authenticatesAndListsSchemaConfigsWithCursorPagination() {
        GraphSchemaManagementService service = mock(GraphSchemaManagementService.class);
        KnowledgeGraphStore graphStore = mock(KnowledgeGraphStore.class);
        GraphSettings settings = mock(GraphSettings.class);
        GraphRequestAuthenticator authenticator = mock(GraphRequestAuthenticator.class);
        Context context = mock(Context.class);
        GraphSchemaSummary first = summary("alpha-schema");
        GraphSchemaSummary second = summary("beta-schema");

        when(context.queryParam("limit")).thenReturn("1");
        when(context.queryParam("cursor")).thenReturn("");
        when(context.json(any())).thenReturn(context);
        when(settings.maxLimit()).thenReturn(200);
        when(service.list()).thenReturn(List.of(first, second));

        GraphSchemaManagementHandler handler =
                new GraphSchemaManagementHandler(service, graphStore, settings, authenticator);
        handler.list(context);

        verify(authenticator).authenticate(context);
        ArgumentCaptor<Object> responseCaptor = ArgumentCaptor.forClass(Object.class);
        verify(context).json(responseCaptor.capture());
        PageResponse<?> page = (PageResponse<?>) responseCaptor.getValue();
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst()).isEqualTo(first);
        assertThat(page.pageInfo().hasMore()).isTrue();
        assertThat(page.pageInfo().nextCursor()).isEqualTo("alpha-schema");
    }

    @Test
    void parsesCreateRequestAndDelegatesToService() {
        GraphSchemaManagementService service = mock(GraphSchemaManagementService.class);
        KnowledgeGraphStore graphStore = mock(KnowledgeGraphStore.class);
        GraphSettings settings = mock(GraphSettings.class);
        GraphRequestAuthenticator authenticator = mock(GraphRequestAuthenticator.class);
        Context context = mock(Context.class);
        GraphSchemaManagementHandler.GraphSchemaWriteRequest request =
                new GraphSchemaManagementHandler.GraphSchemaWriteRequest(
                        "yaml", "schemaId: managed-schema", true);

        when(context.bodyAsClass(GraphSchemaManagementHandler.GraphSchemaWriteRequest.class))
                .thenReturn(request);
        when(context.status(201)).thenReturn(context);

        GraphSchemaManagementHandler handler =
                new GraphSchemaManagementHandler(service, graphStore, settings, authenticator);
        handler.create(context);

        verify(authenticator).authenticate(context);
        verify(service).create(GraphSchemaFormat.YAML, request.content(), true);
    }

    @Test
    void rejectsSchemaDeletionWhileGraphSpacesStillUseIt() {
        GraphSchemaManagementService service = mock(GraphSchemaManagementService.class);
        KnowledgeGraphStore graphStore = mock(KnowledgeGraphStore.class);
        GraphSettings settings = mock(GraphSettings.class);
        GraphRequestAuthenticator authenticator = mock(GraphRequestAuthenticator.class);
        Context context = mock(Context.class);

        when(context.pathParam("schemaId")).thenReturn("student-schema");
        when(context.status(409)).thenReturn(context);
        when(graphStore.hasGraphSpacesForSchema("student-schema")).thenReturn(true);

        GraphSchemaManagementHandler handler =
                new GraphSchemaManagementHandler(service, graphStore, settings, authenticator);
        handler.delete(context);

        verify(graphStore).hasGraphSpacesForSchema("student-schema");
        verify(service, never()).delete("student-schema");
        verify(context).status(409);
    }

    private static GraphSchemaSummary summary(String schemaId) {
        return new GraphSchemaSummary(
                schemaId,
                1,
                GraphSchemaMode.STRICT,
                false,
                GraphSchemaSource.MANAGED,
                GraphSchemaFormat.JSON,
                true,
                1,
                0
        );
    }
}

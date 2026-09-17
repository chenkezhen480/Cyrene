package com.harness.server;

import com.harness.core.model.PageResponse;
import com.harness.graph.config.GraphSettings;
import com.harness.graph.schema.GraphSchemaFormat;
import com.harness.graph.schema.GraphSchemaDetails;
import com.harness.graph.schema.GraphSchemaManagementService;
import com.harness.graph.schema.GraphSchemaMode;
import com.harness.graph.schema.GraphSchemaSource;
import com.harness.graph.schema.GraphSchemaSummary;
import com.harness.tool.knowledge.GraphSchemaWikiCompiler;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

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
        GraphSettings settings = mock(GraphSettings.class);
        GraphRequestAuthenticator authenticator = mock(GraphRequestAuthenticator.class);
        GraphSchemaWikiCompiler wikiCompiler = mock(GraphSchemaWikiCompiler.class);
        Context context = mock(Context.class);
        GraphSchemaSummary first = summary("alpha-schema");
        GraphSchemaSummary second = summary("beta-schema");

        when(context.queryParam("limit")).thenReturn("1");
        when(context.queryParam("cursor")).thenReturn("");
        when(context.json(any())).thenReturn(context);
        when(settings.maxLimit()).thenReturn(200);
        when(service.list()).thenReturn(List.of(first, second));

        GraphSchemaManagementHandler handler =
                new GraphSchemaManagementHandler(
                        service, settings, authenticator, wikiCompiler,
                        mock(GraphDeletionService.class));
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
        GraphSettings settings = mock(GraphSettings.class);
        GraphRequestAuthenticator authenticator = mock(GraphRequestAuthenticator.class);
        GraphSchemaWikiCompiler wikiCompiler = mock(GraphSchemaWikiCompiler.class);
        Context context = mock(Context.class);
        GraphSchemaDetails details = details(true);
        GraphSchemaManagementHandler.GraphSchemaWriteRequest request =
                new GraphSchemaManagementHandler.GraphSchemaWriteRequest(
                        "yaml", "schemaId: managed-schema", true);

        when(context.bodyAsClass(GraphSchemaManagementHandler.GraphSchemaWriteRequest.class))
                .thenReturn(request);
        when(context.status(201)).thenReturn(context);
        when(service.create(GraphSchemaFormat.YAML, request.content(), true))
                .thenReturn(details);

        GraphSchemaManagementHandler handler =
                new GraphSchemaManagementHandler(
                        service, settings, authenticator, wikiCompiler,
                        mock(GraphDeletionService.class));
        handler.create(context);

        verify(authenticator).authenticate(context);
        verify(service).create(GraphSchemaFormat.YAML, request.content(), true);
        verify(wikiCompiler).synchronize(details);
    }

    /**
     * Both states publish a card: creating a Schema has to show what it can answer before it is
     * switched on, and the card itself states when the Schema is not enabled. Only deleting a Schema
     * discontinues its card.
     */
    @Test
    void publishesACardForBothEnabledAndDisabledSchemas() {
        GraphSchemaManagementService service = mock(GraphSchemaManagementService.class);
        GraphSchemaWikiCompiler wikiCompiler = mock(GraphSchemaWikiCompiler.class);
        Context context = mock(Context.class);
        GraphSchemaDetails disabled = details(false);
        GraphSchemaDetails enabled = details(true);

        when(context.pathParam("schemaId")).thenReturn("student-schema");
        when(context.json(any())).thenReturn(context);
        when(service.enable("student-schema")).thenReturn(enabled);
        when(service.disable("student-schema")).thenReturn(disabled);
        GraphSchemaManagementHandler handler = new GraphSchemaManagementHandler(
                service, mock(GraphSettings.class), mock(GraphRequestAuthenticator.class),
                wikiCompiler, mock(GraphDeletionService.class));

        handler.enable(context);
        handler.disable(context);

        verify(wikiCompiler).synchronize(enabled);
        verify(wikiCompiler).synchronize(disabled);
        verify(wikiCompiler, never()).deprecate(any());
    }

    private static GraphSchemaDetails details(boolean enabled) {
        return new GraphSchemaDetails(
                new com.harness.graph.schema.GraphSchemaDefinition(
                        "student-schema", 1, com.harness.graph.schema.GraphSchemaMode.STRICT,
                        Map.of("Student", new com.harness.graph.schema.GraphNodeTypeDefinition(
                                "Student", Map.of())),
                        Map.of(), 1, 2),
                enabled, GraphSchemaSource.MANAGED, GraphSchemaFormat.JSON, true, "{}");
    }

    @Test
    void cascadesSchemaDeletionAndReturnsWhatWasRemoved() {
        GraphSchemaManagementService service = mock(GraphSchemaManagementService.class);
        GraphSettings settings = mock(GraphSettings.class);
        GraphRequestAuthenticator authenticator = mock(GraphRequestAuthenticator.class);
        GraphSchemaWikiCompiler wikiCompiler = mock(GraphSchemaWikiCompiler.class);
        GraphDeletionService deletionService = mock(GraphDeletionService.class);
        Context context = mock(Context.class);
        GraphDeletionService.SchemaDeletionResult result =
                new GraphDeletionService.SchemaDeletionResult(
                        "student-schema", true, true, 2, 7, 5, 3);

        when(context.pathParam("schemaId")).thenReturn("student-schema");
        when(context.json(any())).thenReturn(context);
        when(deletionService.deleteSchema("student-schema")).thenReturn(result);

        GraphSchemaManagementHandler handler = new GraphSchemaManagementHandler(
                service, settings, authenticator, wikiCompiler, deletionService);
        handler.delete(context);

        verify(authenticator).authenticate(context);
        verify(deletionService).deleteSchema("student-schema");
        verify(context).json(result);
    }

    @Test
    void reportsRefusedSchemaDeletionAsConflict() {
        GraphSchemaManagementService service = mock(GraphSchemaManagementService.class);
        GraphDeletionService deletionService = mock(GraphDeletionService.class);
        Context context = mock(Context.class);

        when(context.pathParam("schemaId")).thenReturn("spi-schema");
        when(context.status(409)).thenReturn(context);
        when(context.json(any())).thenReturn(context);
        when(deletionService.deleteSchema("spi-schema")).thenThrow(
                new IllegalStateException("SPI graph schemas are read-only: spi-schema"));

        GraphSchemaManagementHandler handler = new GraphSchemaManagementHandler(
                service, mock(GraphSettings.class), mock(GraphRequestAuthenticator.class),
                mock(GraphSchemaWikiCompiler.class), deletionService);
        handler.delete(context);

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

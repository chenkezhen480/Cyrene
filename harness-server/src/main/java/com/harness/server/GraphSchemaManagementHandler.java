package com.harness.server;

import com.harness.core.model.PageResponse;
import com.harness.graph.config.GraphSettings;
import com.harness.graph.schema.GraphSchemaDetails;
import com.harness.graph.schema.GraphSchemaFormat;
import com.harness.graph.schema.GraphSchemaManagementService;
import com.harness.graph.schema.GraphSchemaSummary;
import io.javalin.http.Context;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class GraphSchemaManagementHandler {

    private final GraphSchemaManagementService schemaService;
    private final GraphSettings settings;
    private final GraphRequestExecutor requestExecutor;

    public GraphSchemaManagementHandler(
            GraphSchemaManagementService schemaService,
            GraphSettings settings
    ) {
        this(schemaService, settings,
                new GraphRequestExecutor(new GraphRequestAuthenticator()));
    }

    GraphSchemaManagementHandler(
            GraphSchemaManagementService schemaService,
            GraphSettings settings,
            GraphRequestAuthenticator requestAuthenticator
    ) {
        this(schemaService, settings, new GraphRequestExecutor(requestAuthenticator));
    }

    GraphSchemaManagementHandler(
            GraphSchemaManagementService schemaService,
            GraphSettings settings,
            GraphRequestExecutor requestExecutor
    ) {
        this.schemaService = Objects.requireNonNull(schemaService, "schemaService");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.requestExecutor = Objects.requireNonNull(requestExecutor, "requestExecutor");
    }

    public void list(Context context) {
        execute(context, () -> {
            int limit = requestedLimit(context);
            String cursor = optionalQuery(context, "cursor");
            List<GraphSchemaSummary> fetched = schemaService.list().stream()
                    .filter(schema -> cursor.isBlank() || schema.schemaId().compareTo(cursor) > 0)
                    .limit((long) limit + 1)
                    .toList();
            context.json(PageResponse.fromFetched(
                    fetched, limit, GraphSchemaSummary::schemaId));
        });
    }

    public void get(Context context) {
        execute(context, () -> {
            context.json(schemaService.get(context.pathParam("schemaId")));
        });
    }

    public void create(Context context) {
        execute(context, () -> {
            GraphSchemaWriteRequest request = context.bodyAsClass(GraphSchemaWriteRequest.class);
            GraphSchemaDetails details = schemaService.create(
                    GraphSchemaFormat.parseEditable(request.format()),
                    request.content(),
                    request.enabled()
            );
            context.status(201).json(details);
        });
    }

    public void update(Context context) {
        execute(context, () -> {
            GraphSchemaWriteRequest request = context.bodyAsClass(GraphSchemaWriteRequest.class);
            context.json(schemaService.update(
                    context.pathParam("schemaId"),
                    GraphSchemaFormat.parseEditable(request.format()),
                    request.content()
            ));
        });
    }

    public void enable(Context context) {
        execute(context, () -> {
            context.json(schemaService.enable(context.pathParam("schemaId")));
        });
    }

    public void disable(Context context) {
        execute(context, () -> {
            context.json(schemaService.disable(context.pathParam("schemaId")));
        });
    }

    public void delete(Context context) {
        execute(context, () -> {
            String schemaId = context.pathParam("schemaId");
            schemaService.delete(schemaId);
            context.json(Map.of("schemaId", schemaId, "deleted", true));
        });
    }

    private int requestedLimit(Context context) {
        return ApiRequestParameters.limit(
                context, settings.defaultLimit(), settings.maxLimit());
    }

    private static String optionalQuery(Context context, String name) {
        return ApiRequestParameters.optionalQuery(context, name);
    }

    private void execute(Context context, HandlerAction action) {
        requestExecutor.execute(context, action::run);
    }

    @FunctionalInterface
    private interface HandlerAction {
        void run();
    }

    public record GraphSchemaWriteRequest(
            String format,
            String content,
            boolean enabled
    ) {
    }
}

package com.harness.server;

import com.harness.core.model.PageResponse;
import com.harness.graph.config.GraphSettings;
import com.harness.graph.schema.GraphSchemaDetails;
import com.harness.graph.schema.GraphSchemaFormat;
import com.harness.graph.schema.GraphSchemaManagementService;
import com.harness.graph.schema.GraphSchemaSummary;
import com.harness.tool.knowledge.GraphSchemaWikiCompiler;
import io.javalin.http.Context;

import java.util.List;
import java.util.Objects;

public final class GraphSchemaManagementHandler {

    private final GraphSchemaManagementService schemaService;
    private final GraphSettings settings;
    private final GraphRequestExecutor requestExecutor;
    private final GraphSchemaWikiCompiler wikiCompiler;
    private final GraphDeletionService deletionService;

    public GraphSchemaManagementHandler(
            GraphSchemaManagementService schemaService,
            GraphSettings settings,
            GraphSchemaWikiCompiler wikiCompiler,
            GraphDeletionService deletionService
    ) {
        this(schemaService, settings,
                new GraphRequestExecutor(new GraphRequestAuthenticator()),
                wikiCompiler, deletionService);
    }

    GraphSchemaManagementHandler(
            GraphSchemaManagementService schemaService,
            GraphSettings settings,
            GraphRequestAuthenticator requestAuthenticator,
            GraphSchemaWikiCompiler wikiCompiler,
            GraphDeletionService deletionService
    ) {
        this(schemaService, settings,
                new GraphRequestExecutor(requestAuthenticator), wikiCompiler, deletionService);
    }

    GraphSchemaManagementHandler(
            GraphSchemaManagementService schemaService,
            GraphSettings settings,
            GraphRequestExecutor requestExecutor,
            GraphSchemaWikiCompiler wikiCompiler,
            GraphDeletionService deletionService
    ) {
        this.schemaService = Objects.requireNonNull(schemaService, "schemaService");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.requestExecutor = Objects.requireNonNull(requestExecutor, "requestExecutor");
        this.wikiCompiler = Objects.requireNonNull(wikiCompiler, "wikiCompiler");
        this.deletionService = Objects.requireNonNull(deletionService, "deletionService");
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
            wikiCompiler.synchronize(details);
            context.status(201).json(details);
        });
    }

    public void update(Context context) {
        execute(context, () -> {
            GraphSchemaWriteRequest request = context.bodyAsClass(GraphSchemaWriteRequest.class);
            GraphSchemaDetails details = schemaService.update(
                    context.pathParam("schemaId"),
                    GraphSchemaFormat.parseEditable(request.format()),
                    request.content()
            );
            wikiCompiler.synchronize(details);
            context.json(details);
        });
    }

    public void enable(Context context) {
        execute(context, () -> {
            GraphSchemaDetails details = schemaService.enable(context.pathParam("schemaId"));
            wikiCompiler.synchronize(details);
            context.json(details);
        });
    }

    public void disable(Context context) {
        execute(context, () -> {
            GraphSchemaDetails details = schemaService.disable(context.pathParam("schemaId"));
            wikiCompiler.synchronize(details);
            context.json(details);
        });
    }

    /** Deletes the Schema and everything scoped to it: graph spaces, bindings, and Wiki cards. */
    public void delete(Context context) {
        execute(context, () -> context.json(
                deletionService.deleteSchema(context.pathParam("schemaId"))));
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

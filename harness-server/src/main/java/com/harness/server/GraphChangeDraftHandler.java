package com.harness.server;

import com.harness.graph.build.*;
import io.javalin.http.Context;
import java.util.Objects;
import java.util.function.Function;

/** Endpoints are guarded by the server's endpoint policy; apply is never a model tool. */
public final class GraphChangeDraftHandler {
    private final GraphChangeDraftService service;
    private final Function<Context, GraphDraftScope> scopeResolver;
    private final GraphRequestExecutor executor;

    public GraphChangeDraftHandler(GraphChangeDraftService service, Function<Context, GraphDraftScope> scopeResolver) {
        this(service, scopeResolver, new GraphRequestExecutor(new GraphRequestAuthenticator()));
    }
    GraphChangeDraftHandler(GraphChangeDraftService service, Function<Context, GraphDraftScope> scopeResolver, GraphRequestExecutor executor) {
        this.service = Objects.requireNonNull(service); this.scopeResolver = Objects.requireNonNull(scopeResolver);
        this.executor = Objects.requireNonNull(executor);
    }
    public void read(Context context) {
        executor.execute(context, () -> context.json(service.read(scopeResolver.apply(context), context.pathParam("draftId"), context.queryParam("expectedContentHash"))));
    }
    public void changes(Context context) {
        executor.execute(context, () -> context.json(service.readPreview(scopeResolver.apply(context), context.pathParam("draftId"),
                context.queryParam("expectedContentHash"), context.queryParam("limit") == null ? 0 : Integer.parseInt(context.queryParam("limit")), context.queryParam("cursor"))));
    }
    public void prepare(Context context) {
        executor.execute(context, () -> context.json(service.prepare(scopeResolver.apply(context), context.bodyAsClass(GraphDraftPrepareRequest.class))));
    }
    public void apply(Context context) {
        executor.execute(context, () -> {
            ApplyRequest request = context.bodyAsClass(ApplyRequest.class);
            context.json(service.apply(scopeResolver.apply(context), context.pathParam("draftId"), request.expectedContentHash()));
        });
    }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"userId", "sessionId"})
    public record ApplyRequest(String expectedContentHash) { }
}

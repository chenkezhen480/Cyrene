package com.harness.server;

import com.harness.graph.build.GraphChangeDraftService;
import com.harness.graph.build.GraphDraftPrepareRequest;
import com.harness.graph.build.GraphDraftScope;
import io.javalin.http.Context;
import java.util.Objects;
import java.util.function.Function;

/** Tenant-wide review is exposed only through separately authorized management endpoints. */
public final class GraphDraftReviewHandler {
    private final GraphChangeDraftService service;
    private final Function<Context, GraphDraftScope> reviewers;
    private final GraphRequestExecutor executor;

    public GraphDraftReviewHandler(GraphChangeDraftService service, Function<Context, GraphDraftScope> reviewers, GraphRequestExecutor executor) {
        this.service = Objects.requireNonNull(service);
        this.reviewers = Objects.requireNonNull(reviewers);
        this.executor = Objects.requireNonNull(executor);
    }
    public void list(Context context) {
        executor.execute(context, () -> context.json(service.listForReview(reviewers.apply(context), limit(context), context.queryParam("cursor"), context.queryParam("status"))));
    }
    public void read(Context context) {
        executor.execute(context, () -> context.json(service.readDetailsForReview(reviewers.apply(context), context.pathParam("draftId"), context.queryParam("expectedContentHash"))));
    }
    public void changes(Context context) {
        executor.execute(context, () -> context.json(service.readPreviewForReview(reviewers.apply(context), context.pathParam("draftId"), context.queryParam("expectedContentHash"), limit(context), context.queryParam("cursor"))));
    }
    public void prepare(Context context) {
        executor.execute(context, () -> context.json(service.prepareForReview(reviewers.apply(context), context.bodyAsClass(GraphDraftPrepareRequest.class))));
    }
    public void apply(Context context) {
        executor.execute(context, () -> {
            var request = context.bodyAsClass(GraphChangeDraftHandler.ApplyRequest.class);
            if (request == null) throw new IllegalArgumentException("Apply request is required");
            context.json(service.applyForReview(reviewers.apply(context), context.pathParam("draftId"), request.expectedContentHash()));
        });
    }
    private static int limit(Context context) {
        return context.queryParam("limit") == null ? 0 : Integer.parseInt(context.queryParam("limit"));
    }
}

package com.harness.server;

import com.harness.graph.build.GraphBuildRequest;
import com.harness.graph.build.GraphBuildService;
import io.javalin.http.Context;

import java.util.Objects;

/**
 * HTTP entry point for explicit graph data conversion and transactional storage.
 */
public final class GraphBuildHandler {

    private final GraphBuildService buildService;
    private final GraphRequestExecutor requestExecutor;

    public GraphBuildHandler(GraphBuildService buildService) {
        this(buildService, new GraphRequestExecutor(new GraphRequestAuthenticator()));
    }

    GraphBuildHandler(
            GraphBuildService buildService,
            GraphRequestAuthenticator requestAuthenticator
    ) {
        this(buildService, new GraphRequestExecutor(requestAuthenticator));
    }

    GraphBuildHandler(
            GraphBuildService buildService,
            GraphRequestExecutor requestExecutor
    ) {
        this.buildService = Objects.requireNonNull(buildService, "buildService");
        this.requestExecutor = Objects.requireNonNull(requestExecutor, "requestExecutor");
    }

    public void build(Context context) {
        requestExecutor.execute(context, () -> {
            GraphBuildRequest request = context.bodyAsClass(GraphBuildRequest.class);
            context.json(buildService.build(request));
        });
    }
}

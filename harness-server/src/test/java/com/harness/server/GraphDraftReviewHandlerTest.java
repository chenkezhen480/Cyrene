package com.harness.server;

import com.harness.graph.build.*;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GraphDraftReviewHandlerTest {
    final GraphChangeDraftService service = mock(GraphChangeDraftService.class);
    final GraphDraftScope reviewer = new GraphDraftScope("tenant", "admin", null, "run", "trace", null, null);
    final GraphRequestAuthenticator authenticator = mock(GraphRequestAuthenticator.class);
    final GraphDraftReviewHandler handler = new GraphDraftReviewHandler(service, context -> reviewer, new GraphRequestExecutor(authenticator));

    @Test void everyOperationUsesTheManagementScopeAndReviewService() {
        var context = context();
        when(context.queryParam("limit")).thenReturn("1");
        when(context.queryParam("cursor")).thenReturn("next");
        when(context.queryParam("status")).thenReturn("PENDING");
        when(context.queryParam("expectedContentHash")).thenReturn("hash");
        var request = mock(GraphDraftPrepareRequest.class);
        when(context.bodyAsClass(GraphDraftPrepareRequest.class)).thenReturn(request);
        handler.list(context);
        handler.read(context);
        handler.changes(context);
        handler.prepare(context);
        handler.apply(context);
        verify(authenticator, times(5)).authenticate(context);
        verify(service).listForReview(reviewer, 1, "next", "PENDING");
        verify(service).readDetailsForReview(reviewer, "draft", "hash");
        verify(service).readPreviewForReview(reviewer, "draft", "hash", 1, "next");
        verify(service).prepareForReview(reviewer, request);
        verify(service).applyForReview(reviewer, "draft", "hash");
        verifyNoMoreInteractions(service);
    }

    @Test void denialConflictAndInvalidRequestAreRenderedAsApiErrors() {
        var forbidden = context();
        when(service.applyForReview(reviewer, "draft", "hash")).thenThrow(new SecurityException("denied"));
        handler.apply(forbidden);
        verify(forbidden).status(403);
        var conflict = context();
        doThrow(new GraphDraftConflictException("stale", "current", "current-hash")).when(service).applyForReview(reviewer, "draft", "hash");
        handler.apply(conflict);
        verify(conflict).status(409);
        var invalid = context();
        when(invalid.queryParam("limit")).thenReturn("invalid");
        handler.list(invalid);
        verify(invalid).status(400);
    }

    private Context context() {
        var context = mock(Context.class);
        when(context.pathParam("draftId")).thenReturn("draft");
        when(context.bodyAsClass(GraphChangeDraftHandler.ApplyRequest.class)).thenReturn(new GraphChangeDraftHandler.ApplyRequest("hash"));
        when(context.status(anyInt())).thenReturn(context);
        when(context.json(any())).thenReturn(context);
        return context;
    }
}

package com.harness.server;

import com.harness.graph.build.*;
import com.harness.graph.model.GraphMutationResult;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;
import com.harness.server.api.ApiError;
import com.harness.server.api.ApiErrorCode;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GraphChangeDraftHandlerTest {
    final GraphChangeDraftService service = mock(GraphChangeDraftService.class);
    final GraphDraftScope scope = new GraphDraftScope("tenant", "user", null, null, null, null, null);
    final GraphRequestAuthenticator auth = mock(GraphRequestAuthenticator.class);
    final GraphChangeDraftHandler handler = new GraphChangeDraftHandler(service, context -> scope, new GraphRequestExecutor(auth));

    @Test void confirmationUsesAuthenticatedReviewerAndFixedHash() {
        Context context = mock(Context.class);
        when(context.pathParam("draftId")).thenReturn("draft");
        when(context.bodyAsClass(GraphChangeDraftHandler.ApplyRequest.class)).thenReturn(new GraphChangeDraftHandler.ApplyRequest("hash"));
        GraphMutationResult result = new GraphMutationResult("request", true, 1, 0);
        when(service.apply(scope, "draft", "hash")).thenReturn(result);
        handler.apply(context);
        verify(auth).authenticate(context);
        verify(context).json(result);
    }

    @Test void staleConfirmationReturns409AndWriteDenial403() {
        Context conflict = context();
        when(service.apply(scope, "draft", "hash")).thenThrow(new IllegalStateException("stale draft"));
        handler.apply(conflict);
        verify(conflict).status(409);
        Context forbidden = context();
        doThrow(new SecurityException("read only")).when(service).apply(scope, "draft", "hash");
        handler.apply(forbidden);
        verify(forbidden).status(403);
    }

    @Test void previewRequiresHashAndReturnsPaginatedContract() {
        Context context = mock(Context.class);
        when(context.pathParam("draftId")).thenReturn("draft");
        when(context.queryParam("expectedContentHash")).thenReturn("hash");
        when(context.queryParam("limit")).thenReturn("1");
        when(context.queryParam("cursor")).thenReturn("cursor");
        handler.changes(context);
        verify(service).readPreview(scope, "draft", "hash", 1, "cursor");
    }

    @Test void authorizedVersionConflictReturnsCurrentReferenceWithoutApplyingIt() {
        Context context = context();
        when(service.apply(scope, "draft", "hash")).thenThrow(
                new GraphDraftConflictException("Draft version is no longer current", "new-draft", "new-hash"));
        handler.apply(context);
        verify(context).status(409);
        ArgumentCaptor<ApiError> error = ArgumentCaptor.forClass(ApiError.class);
        verify(context).json(error.capture());
        assertThat(error.getValue().code()).isEqualTo(ApiErrorCode.CONFLICT);
        assertThat(error.getValue().details()).containsEntry("currentDraftId", "new-draft")
                .containsEntry("currentContentHash", "new-hash");
        verify(service, never()).apply(scope, "new-draft", "new-hash");
    }

    private Context context() {
        Context context = mock(Context.class);
        when(context.pathParam("draftId")).thenReturn("draft");
        when(context.bodyAsClass(GraphChangeDraftHandler.ApplyRequest.class)).thenReturn(new GraphChangeDraftHandler.ApplyRequest("hash"));
        when(context.status(anyInt())).thenReturn(context);
        when(context.json(any())).thenReturn(context);
        return context;
    }
}

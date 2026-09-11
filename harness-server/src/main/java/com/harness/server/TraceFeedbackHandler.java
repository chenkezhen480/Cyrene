package com.harness.server;

import com.harness.server.api.ApiErrorCode;
import com.harness.server.api.ApiResponses;
import io.javalin.http.Context;

import java.util.Map;
import java.util.Objects;

/** HTTP boundary for explicit user feedback bound by traceId. */
final class TraceFeedbackHandler {

    private final TraceFeedbackService feedbackService;
    private final SessionRequestOwnerResolver ownerResolver;

    TraceFeedbackHandler(TraceFeedbackService feedbackService) {
        this(feedbackService, new SessionRequestOwnerResolver());
    }

    TraceFeedbackHandler(
            TraceFeedbackService feedbackService,
            SessionRequestOwnerResolver ownerResolver
    ) {
        this.feedbackService = Objects.requireNonNull(feedbackService, "feedbackService");
        this.ownerResolver = Objects.requireNonNull(ownerResolver, "ownerResolver");
    }

    void update(Context context) {
        Map<String, String> body;
        try {
            body = context.bodyAsClass(Map.class);
        } catch (RuntimeException e) {
            ApiResponses.error(
                    context, 400, ApiErrorCode.INVALID_REQUEST, "Invalid feedback request body");
            return;
        }
        SessionRequestOwnerResolver.Owner owner;
        try {
            owner = ownerResolver.resolve(
                    context, body.get("userId"), body.get("tenantId"));
        } catch (SessionRequestOwnerResolver.OwnerResolutionException e) {
            ApiResponses.error(context, 401, ApiErrorCode.UNAUTHORIZED, e.getMessage());
            return;
        }
        try {
            context.json(feedbackService.update(
                    context.pathParam("traceId"), body.get("feedback"), owner));
        } catch (TraceFeedbackService.InvalidFeedbackException e) {
            ApiResponses.error(context, 400, ApiErrorCode.INVALID_REQUEST, e.getMessage());
        } catch (TraceFeedbackService.TraceNotFoundException e) {
            ApiResponses.error(context, 404, ApiErrorCode.NOT_FOUND, e.getMessage());
        } catch (TraceFeedbackService.TraceOwnershipException e) {
            ApiResponses.error(context, 403, ApiErrorCode.FORBIDDEN, e.getMessage());
        } catch (TraceFeedbackService.FeedbackWriteException e) {
            ApiResponses.error(context, 409, ApiErrorCode.CONFLICT, e.getMessage());
        } catch (RuntimeException e) {
            ApiResponses.error(
                    context, 500, ApiErrorCode.INTERNAL_ERROR,
                    "Failed to persist Trace feedback");
        }
    }
}

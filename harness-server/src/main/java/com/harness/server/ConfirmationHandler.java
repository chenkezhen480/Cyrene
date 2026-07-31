package com.harness.server;

import com.harness.core.exception.AgentException;
import com.harness.input.auth.Authenticator;
import com.harness.server.api.ApiErrorCode;
import com.harness.server.api.ApiResponses;
import com.harness.tool.confirmation.ConfirmationDecision;
import com.harness.tool.confirmation.ConfirmationManager;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * HTTP endpoints for approving or rejecting pending tool executions.
 */
public final class ConfirmationHandler {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationHandler.class);

    private final ConfirmationManager confirmationManager;
    private final Authenticator authenticator;

    public ConfirmationHandler(ConfirmationManager confirmationManager) {
        this.confirmationManager = confirmationManager;
        this.authenticator = new Authenticator();
    }

    public void approve(Context ctx) {
        resolve(ctx, true);
    }

    public void reject(Context ctx) {
        resolve(ctx, false);
    }

    private void resolve(Context ctx, boolean approve) {
        String requestId = ctx.pathParam("requestId");
        try {
            ConfirmationActionRequest request = ctx.bodyAsClass(ConfirmationActionRequest.class);
            validateRequest(request);
            String authenticatedUserId = resolveUserId(ctx, request.userId());

            ConfirmationDecision decision = approve
                    ? confirmationManager.approve(requestId, authenticatedUserId, request.sessionId())
                    : confirmationManager.reject(requestId, authenticatedUserId, request.sessionId());

            log.info("[Confirmation] {} request {} for user={}, session={}",
                    decision, requestId, authenticatedUserId, request.sessionId());
            ctx.json(Map.of(
                    "requestId", requestId,
                    "status", decision.name()));
        } catch (NoSuchElementException e) {
            ApiResponses.error(ctx, 404, ApiErrorCode.NOT_FOUND, e.getMessage());
        } catch (SecurityException e) {
            ApiResponses.error(ctx, 403, ApiErrorCode.FORBIDDEN, e.getMessage());
        } catch (AgentException e) {
            ApiResponses.error(ctx, 401, ApiErrorCode.UNAUTHORIZED, e.getMessage());
        } catch (IllegalStateException e) {
            ApiResponses.error(ctx, 409, ApiErrorCode.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("[Confirmation] Failed to resolve request {}: {}", requestId, e.getMessage(), e);
            ApiResponses.error(ctx, 500, ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    private String resolveUserId(Context ctx, String requestedUserId) {
        String authorization = ctx.header("Authorization");
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7)
                : null;
        String authenticatedUserId = authenticator.authenticate(token);
        if ("anonymous".equals(authenticatedUserId)) {
            if (requestedUserId == null || requestedUserId.isBlank()) {
                throw new IllegalArgumentException("userId is required when authentication is disabled");
            }
            return requestedUserId;
        }
        if (requestedUserId != null && !requestedUserId.isBlank()
                && !Objects.equals(requestedUserId, authenticatedUserId)) {
            throw new SecurityException("Authenticated user does not match confirmation userId");
        }
        return authenticatedUserId;
    }

    private void validateRequest(ConfirmationActionRequest request) {
        if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
    }

    public record ConfirmationActionRequest(String userId, String sessionId) {
    }
}

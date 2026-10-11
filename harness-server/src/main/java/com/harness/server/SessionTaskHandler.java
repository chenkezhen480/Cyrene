package com.harness.server;

import com.harness.agent.AgentRunContext;
import com.harness.agent.SubAgentManager;
import com.harness.core.model.AgentContext;
import com.harness.input.memory.SessionStore;
import com.harness.server.api.ApiErrorCode;
import com.harness.server.api.ApiResponses;
import com.harness.server.security.RequestPrincipalResolver;
import io.javalin.http.Context;

/** Paginated task recall is authorized against the same owner as conversation history. */
final class SessionTaskHandler {
    private final SubAgentManager tasks;
    private final SessionStore sessions;
    private final SessionRequestOwnerResolver owners = new SessionRequestOwnerResolver();

    SessionTaskHandler(SubAgentManager tasks, SessionStore sessions) {
        this.tasks = tasks;
        this.sessions = sessions;
    }

    void list(Context context) {
        try {
            var owner = owners.resolve(context, context.queryParam("userId"), context.queryParam("tenantId"));
            String sessionId = context.pathParam("sessionId");
            if (sessions.findByIdAndOwner(sessionId, owner.userId(), owner.tenantId()).isEmpty()) {
                ApiResponses.error(context, 404, ApiErrorCode.NOT_FOUND, "Session not found: " + sessionId);
                return;
            }
            var principal = context.attribute(RequestPrincipalResolver.PRINCIPAL_ATTRIBUTE);
            String identity = principal instanceof com.harness.core.security.RequestPrincipal authenticated
                    ? authenticated.identity() : AgentContext.DEFAULT_IDENTITY;
            context.json(tasks.listTasks(new AgentRunContext.Owner(owner.userId(), owner.tenantId(), identity),
                    sessionId, context.queryParam("cursor"), ApiRequestParameters.limit(context, 50, 100)));
        } catch (SecurityException denied) {
            ApiResponses.error(context, 403, ApiErrorCode.FORBIDDEN, denied.getMessage());
        } catch (IllegalArgumentException | SessionRequestOwnerResolver.OwnerResolutionException invalid) {
            ApiResponses.error(context, 400, ApiErrorCode.INVALID_REQUEST, invalid.getMessage());
        } catch (RuntimeException failure) {
            ApiResponses.error(context, 500, ApiErrorCode.INTERNAL_ERROR, failure.getMessage());
        }
    }
}

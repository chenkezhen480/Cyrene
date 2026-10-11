package com.harness.server;

import com.harness.agent.SubAgentManager;
import com.harness.core.model.CancellationToken;
import com.harness.server.api.ApiErrorCode;
import com.harness.server.api.ApiResponses;
import io.javalin.http.Context;

import java.util.Map;

/** Cancels both the live request and durable continuations of the addressed session. */
final class ChatCancellationHandler {
    private final Map<String, CancellationToken> activeRequests;
    private final SubAgentManager subAgents;

    ChatCancellationHandler(Map<String, CancellationToken> activeRequests, SubAgentManager subAgents) {
        this.activeRequests = activeRequests;
        this.subAgents = subAgents;
    }

    void cancel(Context context) {
        String sessionId = context.pathParam("sessionId");
        CancellationToken token = activeRequests.get(sessionId);
        if (token != null) token.cancel();
        final boolean cancelledTasks;
        try {
            cancelledTasks = subAgents.cancelSession(sessionId);
        } catch (RuntimeException failure) {
            ApiResponses.error(context, 500, ApiErrorCode.INTERNAL_ERROR, failure.getMessage());
            return;
        }
        if (token == null && !cancelledTasks) {
            ApiResponses.error(context, 404, ApiErrorCode.CHAT_RUN_NOT_ACTIVE,
                    "No active request or pending tasks for session: " + sessionId);
            return;
        }
        context.json(Map.of("status", "cancelled", "sessionId", sessionId));
    }
}

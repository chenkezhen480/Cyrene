package com.harness.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.AgentContext;
import com.harness.graph.build.GraphDraftScope;
import com.harness.input.memory.SessionStore;
import io.javalin.http.Context;

import java.util.function.Function;

/** Binds human draft edits to authenticated ownership and server-generated execution IDs. */
final class GraphDraftScopeResolver implements Function<Context, GraphDraftScope> {
    private final SessionStore sessions;
    private final ObjectMapper mapper;
    private final SessionRequestOwnerResolver owners = new SessionRequestOwnerResolver();

    GraphDraftScopeResolver(SessionStore sessions, ObjectMapper mapper) {
        this.sessions = sessions;
        this.mapper = mapper;
    }

    @Override public GraphDraftScope apply(Context context) {
        JsonNode body = body(context);
        String userId = context.queryParam("userId");
        if (userId == null && body.hasNonNull("userId")) userId = body.get("userId").asText();
        var owner = owners.resolve(context, userId, context.queryParam("tenantId"));
        String sessionId = context.queryParam("sessionId");
        if (sessionId == null && body.hasNonNull("sessionId")) sessionId = body.get("sessionId").asText();
        if (sessionId != null && sessions.findByIdAndOwner(sessionId, owner.userId(), owner.tenantId()).isEmpty()) {
            throw new SecurityException("Draft session does not belong to this owner");
        }
        return new GraphDraftScope(owner.tenantId() == null ? AgentContext.DEFAULT_TENANT_ID : owner.tenantId(),
                owner.userId(), sessionId, java.util.UUID.randomUUID().toString(),
                java.util.UUID.randomUUID().toString(), null, null);
    }

    GraphDraftScope review(Context context) {
        com.harness.core.security.RequestPrincipal principal = context.attribute(
                com.harness.server.security.RequestPrincipalResolver.PRINCIPAL_ATTRIBUTE);
        if (principal == null) throw new SecurityException("Trusted review identity is required");
        JsonNode body = body(context);
        String requestedUserId = context.queryParam("userId");
        if (requestedUserId == null && body.hasNonNull("userId")) requestedUserId = body.get("userId").asText();
        String userId = principal.authenticationType() == com.harness.core.security.RequestPrincipal.AuthenticationType.ANONYMOUS
                ? requestedUserId : principal.requireUserId();
        if (requestedUserId != null && !requestedUserId.equals(userId)) throw new SecurityException("Reviewer does not match authenticated user");
        String requestedTenant = context.queryParam("tenantId");
        if (requestedTenant != null && !requestedTenant.equals(principal.tenantId())) throw new SecurityException("Cross-tenant review is forbidden");
        return new GraphDraftScope(principal.tenantId(), userId, null, java.util.UUID.randomUUID().toString(),
                java.util.UUID.randomUUID().toString(), null, null);
    }

    private JsonNode body(Context context) {
        try {
            JsonNode body = context.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(context.body());
            if (body == null || !body.isObject()) throw new IllegalArgumentException("Draft request JSON must be an object");
            return body;
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("Invalid draft request JSON", invalid);
        }
    }
}

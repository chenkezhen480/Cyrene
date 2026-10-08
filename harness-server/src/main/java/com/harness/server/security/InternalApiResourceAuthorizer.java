package com.harness.server.security;

import com.harness.core.model.ArtifactStore;
import com.harness.core.security.ApiEndpointDescriptor;
import com.harness.core.security.RequestPrincipal;
import com.harness.input.memory.SessionStore;
import com.harness.server.ConfirmationHandler;
import com.harness.server.ChatHandler;
import com.harness.server.StructuredOutputHandler;
import com.harness.server.FileUploadHandler;
import com.harness.agent.voice.VoiceConversationService;
import com.harness.trace.store.TraceStore;
import io.javalin.http.Context;

/** Endpoint permission never substitutes for ownership of the addressed conversation. */
public final class InternalApiResourceAuthorizer {
    public static final String AUTHORIZED_TRACE = InternalApiResourceAuthorizer.class.getName() + ".trace";
    public static final String AUTHORIZED_ARTIFACT = InternalApiResourceAuthorizer.class.getName() + ".artifact";
    private final SessionStore sessions;
    private final TraceStore traces;
    private final ArtifactStore artifacts;
    private final FileUploadHandler inputs;

    public InternalApiResourceAuthorizer(SessionStore sessions, TraceStore traces, ArtifactStore artifacts) {
        this(sessions, traces, artifacts, null);
    }

    public InternalApiResourceAuthorizer(SessionStore sessions, TraceStore traces, ArtifactStore artifacts, FileUploadHandler inputs) {
        this.sessions = sessions;
        this.traces = traces;
        this.artifacts = artifacts;
        this.inputs = inputs;
    }

    public void authorize(Context context, RequestPrincipal principal, ApiEndpointDescriptor endpoint) {
        if (principal.authenticationType() == RequestPrincipal.AuthenticationType.ANONYMOUS) return;
        switch (endpoint.resourcePolicy()) {
            case SESSION -> requireSession(principal, context.pathParam("sessionId"));
            case TRACE -> {
                String id = context.pathParamMap().get("traceId");
                if (id == null) id = context.pathParam("id");
                var trace = traces.findById(id).orElseThrow(InternalApiResourceAuthorizer::denied);
                if (!principal.requireUserId().equals(trace.userId())) throw denied();
                requireSession(principal, trace.sessionId());
                context.attribute(AUTHORIZED_TRACE, trace);
            }
            case ARTIFACT -> {
                if (artifacts == null) throw denied();
                var artifact = artifacts.get(context.pathParam("id")).orElseThrow(InternalApiResourceAuthorizer::denied);
                requireSession(principal, artifact.sessionId());
                context.attribute(AUTHORIZED_ARTIFACT, artifact);
            }
            case USER -> {
                String sessionId = context.header("X-Session-Id");
                if (sessionId != null && !sessionId.isBlank()) requireSession(principal, sessionId);
                if (endpoint.endpointKey().equals("confirmation.approve") || endpoint.endpointKey().equals("confirmation.reject")) {
                    var request = context.bodyAsClass(ConfirmationHandler.ConfirmationActionRequest.class);
                    if (request == null) throw new IllegalArgumentException("Confirmation body is required");
                    requireSession(principal, request.sessionId());
                }
                if (endpoint.endpointKey().equals("chat.create")) {
                    var request = context.bodyAsClass(ChatHandler.ChatRequest.class);
                    requireInputs(principal, request.context());
                } else if (endpoint.endpointKey().equals("chat.structuredOutput")) {
                    var request = context.bodyAsClass(StructuredOutputHandler.StructuredOutputRequest.class);
                    requireInputs(principal, request.context());
                }
            }
            default -> { }
        }
    }

    private void requireInputs(RequestPrincipal principal, java.util.Map<String, Object> context) {
        if (context == null) return;
        Object files = context.get("File");
        if (files instanceof String reference) requireReference(principal, reference);
        else if (files instanceof java.util.List<?> references) {
            for (Object reference : references) {
                if (!(reference instanceof String text)) throw new IllegalArgumentException("context.File requires file references");
                requireReference(principal, text);
            }
        } else if (files != null) throw new IllegalArgumentException("context.File requires file references");
        Object voice = context.get(VoiceConversationService.CONTEXT_VOICE_INPUT);
        if (voice instanceof String reference) requireReference(principal, reference);
        else if (voice != null) throw new IllegalArgumentException("VoiceInput requires a file reference");
    }

    private void requireReference(RequestPrincipal principal, String reference) {
        if (reference.startsWith("/api/artifacts/")) {
            if (artifacts == null) throw denied();
            String id = reference.substring("/api/artifacts/".length());
            if (id.isBlank() || id.contains("/") || id.contains("?")) throw denied();
            requireSession(principal, artifacts.get(id).orElseThrow(InternalApiResourceAuthorizer::denied).sessionId());
            return;
        }
        if (inputs == null) throw new UnsupportedOperationException("Uploaded file authorization is unavailable");
        try { inputs.authorizeReference(reference, principal); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException("Uploaded file authorization failed", e); }
    }

    private void requireSession(RequestPrincipal principal, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) throw denied();
        var session = sessions.findByIdAndOwner(sessionId, principal.requireUserId(), principal.tenantId())
                .orElseThrow(InternalApiResourceAuthorizer::denied);
        if (!sessionId.equals(session.id()) || !principal.userId().equals(session.userId())
                || !principal.tenantId().equals(session.tenantId())) throw denied();
    }

    private static SecurityException denied() { return new SecurityException("Resource access denied"); }
}

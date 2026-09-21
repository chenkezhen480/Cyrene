package com.harness.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.agent.AgentOrchestrator;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.model.AgentContext;
import com.harness.core.model.CancellationToken;
import com.harness.provider.RealtimeEvent;
import com.harness.provider.RealtimeInput;
import com.harness.provider.RealtimeSession;
import com.harness.provider.RealtimeSessionConfig;
import com.harness.provider.RealtimeSessionUpdate;
import com.harness.server.api.ApiErrorCode;
import com.harness.server.api.ApiResponses;
import io.javalin.http.Context;
import io.javalin.websocket.WsBinaryMessageContext;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsErrorContext;
import io.javalin.websocket.WsMessageContext;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** HTTP/WebSocket boundary for server-owned realtime sessions. Reconnect is not supported. */
final class RealtimeSessionHandler implements AutoCloseable {

    private static final int MAX_PENDING_EVENTS = 256;

    private final AgentOrchestrator agent;
    private final ObjectMapper mapper;
    private final SessionRequestOwnerResolver owners = new SessionRequestOwnerResolver();
    private final long timeoutSeconds;
    private final ConcurrentHashMap<String, ManagedSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timeoutExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "realtime-session-timeout");
                thread.setDaemon(true);
                return thread;
            });

    RealtimeSessionHandler(AgentOrchestrator agent, ObjectMapper mapper) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        EnvConfig env = EnvConfig.get();
        this.timeoutSeconds = env.getLong(EnvKey.REALTIME_SESSION_TIMEOUT_SECONDS, 7200);
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException(
                    EnvKey.REALTIME_SESSION_TIMEOUT_SECONDS + " must be positive");
        }
    }

    void create(Context context) {
        try {
            CreateRequest request = context.bodyAsClass(CreateRequest.class);
            SessionRequestOwnerResolver.Owner owner = owners.resolve(
                    context, request.userId(), request.tenantId());
            String identity = optional(request.identity(), AgentContext.DEFAULT_IDENTITY);
            if (identity.length() > 128) {
                throw new IllegalArgumentException("identity must not exceed 128 characters");
            }
            String sessionId = UUID.randomUUID().toString();
            ManagedSession managed = new ManagedSession(
                    sessionId, owner.userId(), owner.tenantId(), identity);
            if (sessions.putIfAbsent(sessionId, managed) != null) {
                throw new IllegalStateException("Realtime session ID collision");
            }
            try {
                RealtimeSessionConfig config = new RealtimeSessionConfig(
                        request.instructions(), request.voice(),
                        request.audioOutput() == null || request.audioOutput(),
                        positive(request.inputSampleRate(), 16_000, "inputSampleRate"),
                        positive(request.outputSampleRate(), 24_000, "outputSampleRate"),
                        turnDetection(request.turnDetection()),
                        java.util.List.of());
                RealtimeSession providerSession = agent.openRealtimeSession(
                        sessionId, owner.tenantId(), owner.userId(), identity,
                        config, managed.cancellationToken, managed::onEvent);
                managed.attach(providerSession);
                managed.timeout = timeoutExecutor.schedule(
                        () -> terminate(sessionId, true), timeoutSeconds, TimeUnit.SECONDS);
            } catch (RuntimeException e) {
                sessions.remove(sessionId, managed);
                managed.closeProvider();
                throw e;
            }
            context.status(201).json(Map.of(
                    "sessionId", sessionId,
                    "provider", agent.realtimeModel().providerName(),
                    "capabilities", agent.realtimeModel().capabilities(),
                    "webSocketPath", "/api/realtime/" + sessionId,
                    "webSocketToken", managed.connectionToken));
        } catch (SessionRequestOwnerResolver.OwnerResolutionException | IllegalArgumentException e) {
            ApiResponses.error(context, 400, ApiErrorCode.INVALID_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            ApiResponses.error(context, 503, ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    void connect(WsConnectContext context) {
        ManagedSession managed = authorized(context);
        if (managed == null) {
            return;
        }
        if (!managed.connect(context)) {
            context.closeSession(1008, "realtime session already has a client");
        }
    }

    void message(WsMessageContext context) {
        ManagedSession managed = authorized(context);
        if (managed == null) {
            return;
        }
        try {
            JsonNode input = mapper.readTree(context.message());
            String type = requiredText(input, "type");
            switch (type) {
                case "text" -> managed.provider().send(
                        new RealtimeInput.TextInput(requiredText(input, "text")));
                case "audio" -> managed.provider().send(new RealtimeInput.AudioChunk(
                        decode(requiredText(input, "data"), "audio")));
                case "image" -> managed.provider().send(new RealtimeInput.ImageFrame(
                        decode(requiredText(input, "data"), "image")));
                case "commit" -> managed.provider().send(new RealtimeInput.CommitTurn());
                case "interrupt" -> managed.provider().interrupt();
                case "update" -> managed.provider().update(new RealtimeSessionUpdate(
                        text(input, "instructions"), text(input, "voice"),
                        input.has("audioOutput") ? input.path("audioOutput").asBoolean() : null,
                        input.has("turnDetection")
                                ? turnDetection(input.path("turnDetection").asText()) : null));
                case "close" -> terminate(managed.sessionId, true);
                default -> throw new IllegalArgumentException("Unknown realtime input type: " + type);
            }
        } catch (Exception e) {
            managed.send(RealtimeEvent.error(managed.sessionId, e.getMessage()));
        }
    }

    void binary(WsBinaryMessageContext context) {
        ManagedSession managed = authorized(context);
        if (managed == null) {
            return;
        }
        byte[] data = Arrays.copyOfRange(
                context.data(), context.offset(), context.offset() + context.length());
        try {
            managed.provider().send(new RealtimeInput.AudioChunk(data));
        } catch (RuntimeException e) {
            managed.send(RealtimeEvent.error(managed.sessionId, e.getMessage()));
        }
    }

    void disconnected(WsCloseContext context) {
        terminate(context.pathParam("sessionId"), true);
    }

    void error(WsErrorContext context) {
        terminate(context.pathParam("sessionId"), true);
    }

    private ManagedSession authorized(WsContext context) {
        String sessionId = context.pathParam("sessionId");
        ManagedSession managed = sessions.get(sessionId);
        if (managed == null) {
            context.closeSession(1008, "unknown or closed realtime session");
            return null;
        }
        try {
            String token = context.queryParam("token");
            if (!managed.matchesToken(token)) {
                throw new SecurityException("invalid realtime WebSocket token");
            }
            return managed;
        } catch (RuntimeException e) {
            context.closeSession(1008, e.getMessage());
            return null;
        }
    }

    private void terminate(String sessionId, boolean closeProvider) {
        ManagedSession managed = sessions.remove(sessionId);
        if (managed != null) {
            if (closeProvider) {
                managed.closeProvider();
            } else {
                managed.finish();
            }
        }
    }

    @Override
    public void close() {
        sessions.keySet().forEach(sessionId -> terminate(sessionId, true));
        timeoutExecutor.shutdownNow();
    }

    private final class ManagedSession {
        private final String sessionId;
        private final String userId;
        private final String connectionToken = UUID.randomUUID().toString();
        @SuppressWarnings("unused")
        private final String tenantId;
        @SuppressWarnings("unused")
        private final String identity;
        private final CancellationToken cancellationToken = new CancellationToken();
        private final AtomicReference<WsContext> client = new AtomicReference<>();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final ArrayDeque<String> pending = new ArrayDeque<>();
        private volatile RealtimeSession provider;
        private volatile ScheduledFuture<?> timeout;

        private ManagedSession(String sessionId, String userId, String tenantId, String identity) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.tenantId = tenantId;
            this.identity = identity;
        }

        private void attach(RealtimeSession provider) {
            this.provider = Objects.requireNonNull(provider, "provider");
        }

        private RealtimeSession provider() {
            RealtimeSession current = provider;
            if (current == null) {
                throw new IllegalStateException("realtime provider session is still opening");
            }
            return current;
        }

        private boolean connect(WsContext context) {
            if (!client.compareAndSet(null, context)) {
                return false;
            }
            synchronized (pending) {
                while (!pending.isEmpty()) {
                    context.send(pending.removeFirst());
                }
            }
            return true;
        }

        private boolean matchesToken(String candidate) {
            if (candidate == null) {
                return false;
            }
            return MessageDigest.isEqual(
                    connectionToken.getBytes(StandardCharsets.UTF_8),
                    candidate.getBytes(StandardCharsets.UTF_8));
        }

        private void onEvent(RealtimeEvent event) {
            send(event);
            if (event.type() == RealtimeEvent.Type.CLOSED) {
                terminate(sessionId, false);
            }
        }

        private void send(RealtimeEvent event) {
            String json = eventJson(event);
            WsContext current = client.get();
            if (current != null) {
                try {
                    current.send(json);
                    return;
                } catch (RuntimeException ignored) {
                    // The close callback owns cleanup.
                }
            }
            synchronized (pending) {
                // ponytail: bounded pre-connect buffer; add per-modality backpressure only if a
                // provider can fill 256 events before the browser completes its WebSocket upgrade.
                if (pending.size() == MAX_PENDING_EVENTS) {
                    pending.removeFirst();
                }
                pending.addLast(json);
            }
        }

        private String eventJson(RealtimeEvent event) {
            ObjectNode json = mapper.createObjectNode();
            json.put("type", event.type().name().toLowerCase(Locale.ROOT));
            json.put("sessionId", sessionId);
            if (event.text() != null) {
                json.put("text", event.text());
            }
            if (event.audio() != null) {
                json.put("audio", Base64.getEncoder().encodeToString(event.audio()));
            }
            if (event.toolCall() != null) {
                json.set("toolCall", mapper.valueToTree(event.toolCall()));
            }
            if (event.usage() != null) {
                json.set("usage", mapper.valueToTree(event.usage()));
            }
            if (event.error() != null) {
                json.put("error", event.error());
            }
            try {
                return mapper.writeValueAsString(json);
            } catch (Exception e) {
                throw new IllegalStateException("Cannot serialize realtime event", e);
            }
        }

        private void closeProvider() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            cancellationToken.cancel();
            cancelTimeout();
            RealtimeSession current = provider;
            if (current != null) {
                current.close();
            }
        }

        private void finish() {
            if (finished.compareAndSet(false, true)) {
                cancellationToken.cancel();
                cancelTimeout();
            }
        }

        private void cancelTimeout() {
            ScheduledFuture<?> current = timeout;
            if (current != null) {
                current.cancel(false);
            }
        }
    }

    private static RealtimeSessionConfig.TurnDetection turnDetection(String raw) {
        if (raw == null || raw.isBlank()) {
            return RealtimeSessionConfig.TurnDetection.SERVER_VAD;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "server_vad" -> RealtimeSessionConfig.TurnDetection.SERVER_VAD;
            case "semantic_vad" -> RealtimeSessionConfig.TurnDetection.SEMANTIC_VAD;
            case "manual" -> RealtimeSessionConfig.TurnDetection.MANUAL;
            default -> throw new IllegalArgumentException("turnDetection must be server_vad, semantic_vad or manual");
        };
    }

    private static int positive(Integer value, int defaultValue, String name) {
        int result = value == null ? defaultValue : value;
        if (result <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return result;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isTextual() || value.asText().isBlank()
                ? null : value.asText();
    }

    private static byte[] decode(String encoded, String name) {
        try {
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " data must be base64", e);
        }
    }

    private static String optional(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    record CreateRequest(
            String userId,
            String tenantId,
            String identity,
            String instructions,
            String voice,
            Boolean audioOutput,
            Integer inputSampleRate,
            Integer outputSampleRate,
            String turnDetection
    ) {
    }
}

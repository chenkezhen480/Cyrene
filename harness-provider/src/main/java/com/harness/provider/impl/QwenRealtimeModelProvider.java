package com.harness.provider.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.model.ModelUsage;
import com.harness.core.model.ToolSpec;
import com.harness.core.modelconfig.ModelConfig;
import com.harness.core.modelconfig.ModelConfigKey;
import com.harness.provider.RealtimeCapabilities;
import com.harness.provider.RealtimeEvent;
import com.harness.provider.RealtimeEventListener;
import com.harness.provider.RealtimeInput;
import com.harness.provider.RealtimeModelProvider;
import com.harness.provider.RealtimeSession;
import com.harness.provider.RealtimeSessionConfig;
import com.harness.provider.RealtimeSessionState;
import com.harness.provider.RealtimeSessionUpdate;
import com.harness.provider.RealtimeToolCall;
import com.harness.provider.RealtimeToolResult;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Qwen-Omni-Realtime WebSocket adapter. */
public final class QwenRealtimeModelProvider implements RealtimeModelProvider {

    private static final Logger log = LoggerFactory.getLogger(QwenRealtimeModelProvider.class);
    private static final int MAX_AUDIO_EVENT_BYTES = 15 * 1024 * 1024;
    private static final int MAX_ENCODED_IMAGE_BYTES = 256 * 1024;

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final OkHttpClient http;
    private final ObjectMapper mapper;

    public QwenRealtimeModelProvider(ModelConfig config) {
        this(config.requireString(ModelConfigKey.REALTIME_API_KEY),
                config.requireString(ModelConfigKey.REALTIME_BASE_URL),
                config.requireString(ModelConfigKey.REALTIME_MODEL),
                new OkHttpClient.Builder()
                        .readTimeout(Duration.ZERO)
                        .pingInterval(Duration.ofSeconds(20))
                        .build(),
                new ObjectMapper());
    }

    QwenRealtimeModelProvider(
            String apiKey,
            String baseUrl,
            String model,
            OkHttpClient http,
            ObjectMapper mapper
    ) {
        this.apiKey = required(apiKey, "apiKey");
        this.baseUrl = websocketUrl(required(baseUrl, "baseUrl"));
        this.model = required(model, "model");
        this.http = Objects.requireNonNull(http, "http");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public RealtimeCapabilities capabilities() {
        return new RealtimeCapabilities(true, true, true, true, true, true, true);
    }

    @Override
    public RealtimeSession open(RealtimeSessionConfig config, RealtimeEventListener listener) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(listener, "listener");
        String separator = baseUrl.contains("?") ? "&" : "?";
        String url = baseUrl + separator + "model="
                + URLEncoder.encode(model, StandardCharsets.UTF_8);
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("User-Agent", "Cyrene-Agent")
                .build();
        return new QwenSession(request, config, listener);
    }

    @Override
    public String providerName() {
        return "qwen";
    }

    private final class QwenSession extends WebSocketListener implements RealtimeSession {

        private final String sessionId = UUID.randomUUID().toString();
        private final RealtimeSessionConfig config;
        private final RealtimeEventListener listener;
        private final AtomicReference<RealtimeSessionState> state =
                new AtomicReference<>(RealtimeSessionState.OPENING);
        private final AtomicBoolean terminalEventSent = new AtomicBoolean();
        private volatile WebSocket socket;
        private volatile boolean suppressAudio;

        private QwenSession(
                Request request,
                RealtimeSessionConfig config,
                RealtimeEventListener listener
        ) {
            this.config = config;
            this.listener = listener;
            this.socket = http.newWebSocket(request, this);
        }

        @Override
        public String sessionId() {
            return sessionId;
        }

        @Override
        public void send(RealtimeInput input) {
            Objects.requireNonNull(input, "input");
            requireUsable();
            switch (input) {
                case RealtimeInput.TextInput text -> sendText(text.text());
                case RealtimeInput.AudioChunk audio -> sendAudio(audio.data());
                case RealtimeInput.ImageFrame image -> sendImage(image.jpeg());
                case RealtimeInput.CommitTurn ignored -> {
                    sendEvent(event("input_audio_buffer.commit"));
                    sendEvent(event("response.create"));
                }
            }
        }

        private void sendText(String text) {
            ObjectNode item = mapper.createObjectNode();
            item.put("type", "message");
            item.put("role", "user");
            ObjectNode content = item.putArray("content").addObject();
            content.put("type", "input_text");
            content.put("text", text);
            ObjectNode request = event("conversation.item.create");
            request.set("item", item);
            sendEvent(request);
            sendEvent(event("response.create"));
        }

        private void sendAudio(byte[] data) {
            if (data.length > MAX_AUDIO_EVENT_BYTES) {
                throw new IllegalArgumentException("audio chunk exceeds 15 MiB");
            }
            ObjectNode request = event("input_audio_buffer.append");
            request.put("audio", Base64.getEncoder().encodeToString(data));
            sendEvent(request);
        }

        private void sendImage(byte[] data) {
            String encoded = Base64.getEncoder().encodeToString(data);
            if (encoded.length() > MAX_ENCODED_IMAGE_BYTES) {
                throw new IllegalArgumentException("base64 image exceeds 256 KiB");
            }
            ObjectNode request = event("input_image_buffer.append");
            request.put("image", encoded);
            sendEvent(request);
        }

        @Override
        public void update(RealtimeSessionUpdate update) {
            Objects.requireNonNull(update, "update");
            requireUsable();
            ObjectNode session = mapper.createObjectNode();
            if (update.instructions() != null) {
                session.put("instructions", update.instructions());
            }
            if (update.voice() != null) {
                session.put("voice", update.voice());
            }
            if (update.audioOutput() != null) {
                modalities(session, update.audioOutput());
            }
            if (update.turnDetection() != null) {
                turnDetection(session, update.turnDetection());
            }
            ObjectNode request = event("session.update");
            request.set("session", session);
            sendEvent(request);
        }

        @Override
        public void sendToolResult(RealtimeToolResult result) {
            Objects.requireNonNull(result, "result");
            requireUsable();
            ObjectNode item = mapper.createObjectNode();
            item.put("type", "function_call_output");
            item.put("call_id", result.callId());
            item.put("output", result.error() ? "ERROR: " + result.output() : result.output());
            ObjectNode request = event("conversation.item.create");
            request.set("item", item);
            sendEvent(request);
            sendEvent(event("response.create"));
        }

        @Override
        public void interrupt() {
            if (!state.compareAndSet(RealtimeSessionState.ACTIVE,
                    RealtimeSessionState.INTERRUPTING)) {
                return;
            }
            suppressAudio = true;
            sendEvent(event("response.cancel"));
        }

        @Override
        public RealtimeSessionState state() {
            return state.get();
        }

        @Override
        public void close() {
            RealtimeSessionState previous = state.getAndUpdate(current -> switch (current) {
                case CLOSED, FAILED -> current;
                default -> RealtimeSessionState.CLOSING;
            });
            if (previous == RealtimeSessionState.CLOSED
                    || previous == RealtimeSessionState.FAILED
                    || previous == RealtimeSessionState.CLOSING) {
                return;
            }
            WebSocket current = socket;
            if (current != null) {
                sendEvent(event("session.finish"));
                if (!current.close(1000, "client close")) {
                    current.cancel();
                }
            }
            state.set(RealtimeSessionState.CLOSED);
            emitTerminal();
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            this.socket = webSocket;
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            final JsonNode source;
            try {
                source = mapper.readTree(text);
            } catch (Exception e) {
                emit(RealtimeEvent.error(sessionId, "Invalid Qwen realtime event: " + e.getMessage()));
                return;
            }
            String type = source.path("type").asText();
            if ("session.created".equals(type)) {
                sendInitialConfiguration();
                return;
            }
            if ("session.updated".equals(type)) {
                state.set(RealtimeSessionState.READY);
            } else if ("response.created".equals(type)) {
                state.set(RealtimeSessionState.ACTIVE);
                suppressAudio = false;
            } else if ("input_audio_buffer.speech_started".equals(type)
                    && state.get() == RealtimeSessionState.ACTIVE) {
                interrupt();
            } else if ("response.done".equals(type)) {
                if (state.get() != RealtimeSessionState.CLOSING
                        && state.get() != RealtimeSessionState.CLOSED
                        && state.get() != RealtimeSessionState.FAILED) {
                    state.set(RealtimeSessionState.READY);
                }
            }
            for (RealtimeEvent event : mapEvent(source, sessionId, !suppressAudio)) {
                emit(event);
            }
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            webSocket.close(code, reason);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            state.compareAndSet(RealtimeSessionState.CLOSING, RealtimeSessionState.CLOSED);
            if (state.get() != RealtimeSessionState.FAILED) {
                state.set(RealtimeSessionState.CLOSED);
            }
            emitTerminal();
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable failure, Response response) {
            state.set(RealtimeSessionState.FAILED);
            emit(RealtimeEvent.error(sessionId, failure.getMessage()));
            emitTerminal();
        }

        private void sendInitialConfiguration() {
            ObjectNode session = mapper.createObjectNode();
            modalities(session, config.audioOutput());
            if (config.voice() != null && !config.voice().isBlank()) {
                session.put("voice", config.voice());
            }
            if (config.instructions() != null && !config.instructions().isBlank()) {
                session.put("instructions", config.instructions());
            }
            ObjectNode audio = session.putObject("audio");
            audio.putObject("input").putObject("format")
                    .put("type", "pcm").put("sample_rate", config.inputSampleRate());
            audio.putObject("output").putObject("format")
                    .put("type", "pcm").put("sample_rate", config.outputSampleRate());
            turnDetection(session, config.turnDetection());
            addTools(session, config.tools());
            ObjectNode request = event("session.update");
            request.set("session", session);
            sendEvent(request);
        }

        private void addTools(ObjectNode session, List<ToolSpec> tools) {
            if (tools.isEmpty()) {
                return;
            }
            ArrayNode output = session.putArray("tools");
            for (ToolSpec spec : tools) {
                ObjectNode function = output.addObject().put("type", "function")
                        .putObject("function");
                function.put("name", spec.name());
                function.put("description", spec.description());
                function.set("parameters", spec.parameters());
            }
        }

        private void requireUsable() {
            RealtimeSessionState current = state.get();
            if (current == RealtimeSessionState.CLOSING
                    || current == RealtimeSessionState.CLOSED
                    || current == RealtimeSessionState.FAILED) {
                throw new IllegalStateException("realtime session is " + current);
            }
        }

        private void sendEvent(ObjectNode event) {
            try {
                WebSocket current = socket;
                if (current == null || !current.send(mapper.writeValueAsString(event))) {
                    throw new IllegalStateException("Qwen realtime connection is not writable");
                }
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Cannot serialize Qwen realtime event", e);
            }
        }

        private ObjectNode event(String type) {
            ObjectNode event = mapper.createObjectNode();
            event.put("event_id", "event_" + UUID.randomUUID());
            event.put("type", type);
            return event;
        }

        private void emit(RealtimeEvent event) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException e) {
                log.warn("Realtime listener failed for session {}: {}", sessionId, e.getMessage());
            }
        }

        private void emitTerminal() {
            if (terminalEventSent.compareAndSet(false, true)) {
                emit(RealtimeEvent.simple(RealtimeEvent.Type.CLOSED, sessionId));
            }
        }
    }

    static List<RealtimeEvent> mapEvent(JsonNode source, String sessionId, boolean acceptAudio) {
        String type = source.path("type").asText();
        return switch (type) {
            case "session.updated" -> List.of(RealtimeEvent.simple(
                    RealtimeEvent.Type.SESSION_READY, sessionId));
            case "input_audio_buffer.speech_started" -> List.of(RealtimeEvent.simple(
                    RealtimeEvent.Type.USER_SPEECH_STARTED, sessionId));
            case "input_audio_buffer.speech_stopped" -> List.of(RealtimeEvent.simple(
                    RealtimeEvent.Type.USER_SPEECH_STOPPED, sessionId));
            case "conversation.item.input_audio_transcription.delta" -> List.of(
                    RealtimeEvent.text(RealtimeEvent.Type.USER_TRANSCRIPT_DELTA, sessionId,
                            source.path("text").asText() + source.path("stash").asText()));
            case "conversation.item.input_audio_transcription.completed" -> List.of(
                    RealtimeEvent.text(RealtimeEvent.Type.USER_TRANSCRIPT_DONE, sessionId,
                            source.path("transcript").asText()));
            case "response.text.delta", "response.audio_transcript.delta" -> List.of(
                    RealtimeEvent.text(RealtimeEvent.Type.ASSISTANT_TEXT_DELTA, sessionId,
                            source.path("delta").asText()));
            case "response.text.done" -> List.of(RealtimeEvent.text(
                    RealtimeEvent.Type.ASSISTANT_TEXT_DONE, sessionId,
                    source.path("text").asText()));
            case "response.audio_transcript.done" -> List.of(RealtimeEvent.text(
                    RealtimeEvent.Type.ASSISTANT_TEXT_DONE, sessionId,
                    source.path("transcript").asText()));
            case "response.audio.delta" -> acceptAudio
                    ? List.of(new RealtimeEvent(RealtimeEvent.Type.ASSISTANT_AUDIO_DELTA,
                            sessionId, null, decodeAudio(source.path("delta").asText()),
                            null, null, null))
                    : List.of();
            case "response.function_call_arguments.done" -> List.of(new RealtimeEvent(
                    RealtimeEvent.Type.TOOL_CALL, sessionId, null, null,
                    new RealtimeToolCall(source.path("call_id").asText(),
                            source.path("name").asText(), source.path("arguments").asText()),
                    null, null));
            case "response.done" -> doneEvents(source, sessionId);
            case "error" -> List.of(RealtimeEvent.error(
                    sessionId, source.path("error").path("message").asText("Unknown Qwen error")));
            default -> List.of();
        };
    }

    private static List<RealtimeEvent> doneEvents(JsonNode source, String sessionId) {
        List<RealtimeEvent> events = new ArrayList<>();
        JsonNode usage = source.path("response").path("usage");
        if (!usage.isMissingNode() && !usage.isNull()) {
            ModelUsage mapped = new ModelUsage(
                    nullableLong(usage, "input_tokens"), null, null,
                    nullableLong(usage, "output_tokens"), null, 0, null, null);
            events.add(new RealtimeEvent(RealtimeEvent.Type.USAGE, sessionId,
                    null, null, null, mapped, null));
        }
        events.add(RealtimeEvent.simple(RealtimeEvent.Type.TURN_DONE, sessionId));
        return List.copyOf(events);
    }

    private static Long nullableLong(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || !value.isNumber() ? null : value.longValue();
    }

    private static byte[] decodeAudio(String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid base64 audio from Qwen", e);
        }
    }

    private static void modalities(ObjectNode session, boolean audioOutput) {
        ArrayNode modalities = session.putArray("modalities");
        modalities.add("text");
        if (audioOutput) {
            modalities.add("audio");
        }
    }

    private static void turnDetection(
            ObjectNode session,
            RealtimeSessionConfig.TurnDetection detection
    ) {
        if (detection == RealtimeSessionConfig.TurnDetection.MANUAL) {
            session.putNull("turn_detection");
            return;
        }
        session.putObject("turn_detection").put("type",
                detection == RealtimeSessionConfig.TurnDetection.SEMANTIC_VAD
                        ? "semantic_vad" : "server_vad");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String websocketUrl(String raw) {
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("realtime baseUrl is invalid", e);
        }
        if (!"wss".equalsIgnoreCase(uri.getScheme())
                && !"ws".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("realtime baseUrl must use ws or wss");
        }
        return raw;
    }
}

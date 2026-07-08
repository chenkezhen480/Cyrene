package com.harness.core.model;

import java.util.Map;

/**
 * A single event emitted during streaming agent execution.
 */
public record StreamEvent(
        Type type,
        String data,
        Map<String, Object> metadata
) {
    public enum Type {
        START,
        TOKEN,
        STEP,
        COMPRESS,
        DONE,
        CANCELLED,
        ERROR
    }

    public static StreamEvent start(String sessionId) {
        return new StreamEvent(Type.START, "", Map.of(
                "sessionId", sessionId != null ? sessionId : ""
        ));
    }

    public static StreamEvent token(String text) {
        return new StreamEvent(Type.TOKEN, text, Map.of());
    }

    public static StreamEvent step(ReActStep step) {
        return new StreamEvent(Type.STEP, "", Map.of("step", step));
    }

    public static StreamEvent done(String output, String traceId, String sessionId, int steps) {
        return new StreamEvent(Type.DONE, output, Map.of(
                "traceId", traceId,
                "sessionId", sessionId != null ? sessionId : "",
                "steps", steps
        ));
    }

    public static StreamEvent error(String message) {
        return new StreamEvent(Type.ERROR, message, Map.of());
    }

    public static StreamEvent cancelled() {
        return new StreamEvent(Type.CANCELLED, "Output cancelled by user", Map.of());
    }

    public static StreamEvent compress(String mode, String detail) {
        return new StreamEvent(Type.COMPRESS, detail, Map.of("mode", mode));
    }
}

package com.harness.core.model;

import java.util.List;
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
        TOOL_CALL_START,
        COMPRESS,
        ARTIFACT,
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

    public static StreamEvent toolCallStart(String toolName, String arguments) {
        return new StreamEvent(Type.TOOL_CALL_START, "", Map.of(
                "toolName", toolName != null ? toolName : "",
                "arguments", arguments != null ? arguments : ""
        ));
    }

    public static StreamEvent done(String output, String traceId, String sessionId, int steps) {
        return new StreamEvent(Type.DONE, output, Map.of(
                "traceId", traceId,
                "sessionId", sessionId != null ? sessionId : "",
                "steps", steps
        ));
    }

    public static StreamEvent done(String output, String traceId, String sessionId, int steps, java.util.List<Artifact> artifacts) {
        Map<String, Object> meta = new java.util.HashMap<>();
        meta.put("traceId", traceId);
        meta.put("sessionId", sessionId != null ? sessionId : "");
        meta.put("steps", steps);
        meta.put("artifacts", artifacts != null ? artifacts.stream().map(a -> Map.of(
                "id", a.id(),
                "name", a.name() != null ? a.name() : "",
                "type", a.type().name(),
                "mimeType", a.mimeType() != null ? a.mimeType() : "",
                "sizeBytes", a.sizeBytes(),
                "downloadUrl", a.downloadUrl(),
                "previewUrl", a.previewUrl()
        )).toList() : List.of());
        return new StreamEvent(Type.DONE, output, meta);
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

    public static StreamEvent artifact(Artifact artifact) {
        return new StreamEvent(Type.ARTIFACT, artifact.name(), Map.of(
                "artifactId", artifact.id(),
                "name", artifact.name(),
                "type", artifact.type().name(),
                "mimeType", artifact.mimeType() != null ? artifact.mimeType() : "",
                "sizeBytes", artifact.sizeBytes(),
                "downloadUrl", artifact.downloadUrl(),
                "previewUrl", artifact.previewUrl()
        ));
    }
}

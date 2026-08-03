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
        TOOL_CALL_CREATED,
        TOOL_CALL_START,
        TOOL_CALL_DONE,
        CONFIRMATION_REQUIRED,
        CONFIRMATION_RESOLVED,
        COMPRESS,
        ARTIFACT,
        AUDIO_START,
        AUDIO_DELTA,
        AUDIO_CHUNK_DONE,
        AUDIO_DONE,
        AUDIO_ERROR,
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

    public static StreamEvent toolCallCreated(String toolName, String arguments) {
        return new StreamEvent(Type.TOOL_CALL_CREATED, "", Map.of(
                "toolName", toolName != null ? toolName : "",
                "arguments", arguments != null ? arguments : ""
        ));
    }

    public static StreamEvent toolCallStart(String toolName, String arguments) {
        return new StreamEvent(Type.TOOL_CALL_START, "", Map.of(
                "toolName", toolName != null ? toolName : "",
                "arguments", arguments != null ? arguments : ""
        ));
    }

    public static StreamEvent toolCallDone(String toolName, boolean success, long durationMs) {
        return new StreamEvent(Type.TOOL_CALL_DONE, "", Map.of(
                "toolName", toolName != null ? toolName : "",
                "success", success,
                "durationMs", durationMs
        ));
    }

    public static StreamEvent confirmationRequired(
            String requestId,
            String toolName,
            Object arguments,
            String argumentsHash,
            String summary,
            String riskLevel,
            String expiresAt) {
        Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("requestId", requestId);
        metadata.put("toolName", toolName);
        metadata.put("arguments", arguments);
        metadata.put("argumentsHash", argumentsHash);
        metadata.put("summary", summary != null ? summary : "");
        metadata.put("riskLevel", riskLevel);
        metadata.put("expiresAt", expiresAt);
        return new StreamEvent(Type.CONFIRMATION_REQUIRED, "", metadata);
    }

    public static StreamEvent confirmationResolved(
            String requestId, String toolName, String decision) {
        return new StreamEvent(Type.CONFIRMATION_RESOLVED, "", Map.of(
                "requestId", requestId,
                "toolName", toolName,
                "decision", decision
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
        return done(output, traceId, sessionId, steps, artifacts, false);
    }

    public static StreamEvent done(String output, String traceId, String sessionId, int steps,
                                   java.util.List<Artifact> artifacts, boolean requiresConfirmation) {
        Map<String, Object> meta = new java.util.HashMap<>();
        meta.put("traceId", traceId);
        meta.put("sessionId", sessionId != null ? sessionId : "");
        meta.put("steps", steps);
        meta.put("requiresConfirmation", requiresConfirmation);
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

    public static StreamEvent audioStart(long sequence, String mimeType) {
        return new StreamEvent(Type.AUDIO_START, "", Map.of(
                "sequence", sequence,
                "mimeType", mimeType != null ? mimeType : "application/octet-stream"
        ));
    }

    public static StreamEvent audioDelta(long sequence, String mimeType, String base64Data) {
        return new StreamEvent(Type.AUDIO_DELTA, base64Data != null ? base64Data : "", Map.of(
                "sequence", sequence,
                "mimeType", mimeType != null ? mimeType : "application/octet-stream"
        ));
    }

    public static StreamEvent audioChunkDone(long sequence) {
        return new StreamEvent(Type.AUDIO_CHUNK_DONE, "", Map.of("sequence", sequence));
    }

    public static StreamEvent audioDone() {
        return new StreamEvent(Type.AUDIO_DONE, "", Map.of());
    }

    public static StreamEvent audioError(String code, String message) {
        return new StreamEvent(Type.AUDIO_ERROR, message != null ? message : "Voice output failed", Map.of(
                "code", code != null ? code : "VOICE_OUTPUT_FAILED"
        ));
    }
}

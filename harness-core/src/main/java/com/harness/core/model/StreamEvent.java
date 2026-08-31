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
        TOOL_OUTPUT,
        CONFIRMATION_REQUIRED,
        CONFIRMATION_RESOLVED,
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

    public static StreamEvent toolCallCreated(
            String toolCallId, String toolName, String arguments) {
        return new StreamEvent(Type.TOOL_CALL_CREATED, "", Map.of(
                "toolCallId", requiredToolCallId(toolCallId),
                "toolName", toolName != null ? toolName : "",
                "status", ToolCallStatus.CREATED.name(),
                "arguments", arguments != null ? arguments : ""
        ));
    }

    public static StreamEvent toolCallStart(
            String toolCallId, String toolName, String arguments) {
        return new StreamEvent(Type.TOOL_CALL_START, "", Map.of(
                "toolCallId", requiredToolCallId(toolCallId),
                "toolName", toolName != null ? toolName : "",
                "status", ToolCallStatus.RUNNING.name(),
                "arguments", arguments != null ? arguments : ""
        ));
    }

    public static StreamEvent toolCallDone(
            String toolCallId,
            String toolName,
            ToolCallStatus status,
            long durationMs,
            String errorSummary) {
        if (status != ToolCallStatus.SUCCEEDED
                && status != ToolCallStatus.FAILED
                && status != ToolCallStatus.CANCELLED) {
            throw new IllegalArgumentException("Tool completion requires a terminal status");
        }
        Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("toolCallId", requiredToolCallId(toolCallId));
        metadata.put("toolName", toolName != null ? toolName : "");
        metadata.put("status", status.name());
        metadata.put("durationMs", durationMs);
        metadata.put("errorSummary", errorSummary != null ? errorSummary : "");
        return new StreamEvent(Type.TOOL_CALL_DONE, "", Map.copyOf(metadata));
    }

    public static StreamEvent toolOutput(
            String toolCallId, String toolName, ToolOutput output) {
        if (output == null) {
            throw new IllegalArgumentException("output must not be null");
        }
        return new StreamEvent(Type.TOOL_OUTPUT, "", Map.of(
                "toolCallId", requiredToolCallId(toolCallId),
                "toolName", toolName != null ? toolName : "",
                "output", output
        ));
    }

    public static StreamEvent confirmationRequired(
            String toolCallId,
            String requestId,
            String toolName,
            Object arguments,
            String argumentsHash,
            String summary,
            String riskLevel,
            String expiresAt) {
        Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("toolCallId", requiredToolCallId(toolCallId));
        metadata.put("requestId", requestId);
        metadata.put("toolName", toolName);
        metadata.put("arguments", arguments);
        metadata.put("argumentsHash", argumentsHash);
        metadata.put("summary", summary != null ? summary : "");
        metadata.put("riskLevel", riskLevel);
        metadata.put("expiresAt", expiresAt);
        metadata.put("status", ToolCallStatus.AWAITING_CONFIRMATION.name());
        return new StreamEvent(Type.CONFIRMATION_REQUIRED, "", metadata);
    }

    public static StreamEvent confirmationResolved(
            String toolCallId,
            String requestId,
            String toolName,
            String decision,
            ToolCallStatus status) {
        return new StreamEvent(Type.CONFIRMATION_RESOLVED, "", Map.of(
                "toolCallId", requiredToolCallId(toolCallId),
                "requestId", requestId,
                "toolName", toolName,
                "decision", decision,
                "status", status.name()
        ));
    }

    private static String requiredToolCallId(String toolCallId) {
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId must not be blank");
        }
        return toolCallId;
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

}

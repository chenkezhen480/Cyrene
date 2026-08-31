package com.harness.server;

import com.harness.core.model.Artifact;
import com.harness.core.model.StreamEvent;
import com.harness.core.model.ToolOutput;

import java.util.HashMap;
import java.util.Map;

/** Maps internal Tool output into a bounded user-interface SSE payload. */
final class ToolOutputSseMapper {

    static final int MAX_TEXT_CODE_POINTS = 100;

    private ToolOutputSseMapper() {
    }

    static Map<String, Object> toPayload(StreamEvent event) {
        Object outputValue = event.metadata().get("output");
        if (!(outputValue instanceof ToolOutput output)) {
            throw new IllegalArgumentException("Tool output event is missing typed output");
        }

        String text = output.text() != null ? output.text() : "";
        int textLength = text.codePointCount(0, text.length());
        Map<String, Object> payload = new HashMap<>();
        payload.put("toolCallId", event.metadata().get("toolCallId"));
        payload.put("toolName", event.metadata().get("toolName"));
        payload.put("text", truncate(text, textLength));
        payload.put("textLength", textLength);
        payload.put("truncated", textLength > MAX_TEXT_CODE_POINTS);
        payload.put("artifacts", output.artifacts().stream()
                .map(ToolOutputSseMapper::artifactPayload)
                .toList());
        payload.put("data", output.json());
        return payload;
    }

    private static String truncate(String text, int textLength) {
        if (textLength <= MAX_TEXT_CODE_POINTS) {
            return text;
        }
        int endIndex = text.offsetByCodePoints(0, MAX_TEXT_CODE_POINTS);
        return text.substring(0, endIndex);
    }

    private static Map<String, Object> artifactPayload(Artifact artifact) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("artifactId", artifact.id());
        payload.put("name", artifact.name() != null ? artifact.name() : "");
        payload.put("type", artifact.type().name());
        payload.put("mimeType", artifact.mimeType() != null ? artifact.mimeType() : "");
        payload.put("sizeBytes", artifact.sizeBytes());
        payload.put("downloadUrl", artifact.downloadUrl());
        payload.put("previewUrl", artifact.previewUrl());
        return Map.copyOf(payload);
    }
}

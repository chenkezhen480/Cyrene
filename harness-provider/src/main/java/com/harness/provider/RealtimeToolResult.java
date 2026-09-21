package com.harness.provider;

public record RealtimeToolResult(String callId, String output, boolean error) {
    public RealtimeToolResult {
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("callId is required");
        }
        output = output == null ? "" : output;
    }
}

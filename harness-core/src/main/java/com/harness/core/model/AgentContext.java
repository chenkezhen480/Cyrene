package com.harness.core.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Context passed through the agent pipeline.
 * Contains caller metadata that influences behavior (e.g., outputMode).
 */
public record AgentContext(
        Map<String, Object> data
) {
    public static final String KEY_USER_ID = "userId";
    public static final String KEY_OUTPUT_MODE = "outputMode";
    public static final String KEY_ENABLE_THINKING = "enableThinking";
    public static final String KEY_CREDENTIALS = "credentials";
    public static final String VALUE_MODE_BLOCKING = "blocking";
    public static final String VALUE_MODE_STREAMING = "streaming";

    public static AgentContext of(Map<String, Object> data) {
        return new AgentContext(data != null ? data : Map.of());
    }

    public static AgentContext empty() {
        return new AgentContext(Map.of());
    }

    public String outputMode() {
        Object mode = data.get(KEY_OUTPUT_MODE);
        return mode != null ? mode.toString() : VALUE_MODE_BLOCKING;
    }

    public boolean isStreaming() {
        return VALUE_MODE_STREAMING.equals(outputMode());
    }

    public String userId() {
        Object id = data.get(KEY_USER_ID);
        return id != null ? id.toString() : null;
    }

    public Boolean enableThinking() {
        Object val = data.get(KEY_ENABLE_THINKING);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return Boolean.parseBoolean(s);
        return null;  // not specified, use env default
    }

    /**
     * Per-request reflection interval override.
     * context JSON: {"reflectionInterval": 5} 覆盖 env 默认值
     */
    public Integer reflectionInterval() {
        Object val = data.get("reflectionInterval");
        if (val instanceof Number n) return n.intValue();
        return null;  // use env default
    }

    /**
     * Per-request credentials map for user_passthrough auth.
     * Keys match {@code credentialKey} in {@code project-apis.json} endpoint declarations.
     * Values are the raw tokens to inject into downstream HTTP requests.
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> credentials() {
        Object val = data.get(KEY_CREDENTIALS);
        if (val instanceof Map<?, ?> m) {
            Map<String, String> result = new HashMap<>();
            m.forEach((k, v) -> {
                if (k != null && v != null) result.put(k.toString(), v.toString());
            });
            return result;
        }
        return Map.of();
    }

    /**
     * Create a shallow clone with credentials cleared (for sub-agent isolation).
     */
    @SuppressWarnings("unchecked")
    public AgentContext withClearedCredentials() {
        Map<String, Object> copy = new HashMap<>(data);
        copy.put(KEY_CREDENTIALS, Map.of());
        return new AgentContext(copy);
    }
}

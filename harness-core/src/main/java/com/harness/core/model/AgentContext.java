package com.harness.core.model;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Context passed through the agent pipeline.
 * Contains caller metadata that influences behavior.
 */
public record AgentContext(
        Map<String, Object> data
) {
    public static final String KEY_USER_ID = "userId";
    public static final String KEY_TENANT_ID = "tenantId";
    public static final String DEFAULT_TENANT_ID = "000000";
    public static final String KEY_OUTPUT_MODE = "outputMode";
    public static final String KEY_ENABLE_THINKING = "enableThinking";
    public static final String KEY_CREDENTIALS = "credentials";
    public static final String KEY_GRAPH_REQUEST_CONTEXT = "graphRequestContext";
    public static final String KEY_KNOWLEDGE_REQUEST_CONTEXT = "knowledgeRequestContext";
    public static final String KEY_NEEDS_GRAPH_KNOWLEDGE = "needsGraphKnowledge";

    // ==================== GapAnalysis 显式覆盖字段 ====================
    /** 是否检索知识库，null 时回退全局配置 */
    public static final String KEY_NEEDS_KNOWLEDGE_BASE = "needsKnowledgeBase";
    /** 是否联网搜索，null 时回退全局配置 */
    public static final String KEY_NEEDS_WEB_SEARCH = "needsWebSearch";
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

    /**
     * Optional tenant identifier supplied by the trusted backend caller.
     * Standalone integrations share the fixed default tenant.
     */
    public String tenantId() {
        Object value = data.get(KEY_TENANT_ID);
        if (value == null || value.toString().isBlank()) {
            return DEFAULT_TENANT_ID;
        }
        String tenantId = value.toString().trim();
        if (tenantId.length() > 128) {
            throw new IllegalArgumentException("tenantId must not exceed 128 characters");
        }
        return tenantId;
    }

    public Boolean enableThinking() {
        Object val = data.get(KEY_ENABLE_THINKING);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return Boolean.parseBoolean(s);
        return null;  // not specified, use env default
    }

    // ==================== GapAnalysis 显式覆盖 ====================
    // 约定：null = 未显式指定，回退到环境变量默认值；显式值（false/true/NONE 等）优先于环境变量

    /**
     * 是否检索知识库。
     * <ul>
     *   <li>{@code null} — 未指定，回退到环境变量（HARNESS_RAG_PROVIDER 等）</li>
     *   <li>{@code true} — 显式启用检索</li>
     *   <li>{@code false} — 显式禁用检索</li>
     * </ul>
     * context JSON: {"needsKnowledgeBase": true}
     */
    public Boolean needsKnowledgeBase() {
        Object val = data.get(KEY_NEEDS_KNOWLEDGE_BASE);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return Boolean.parseBoolean(s);
        return null;
    }

    /**
     * Parse the server-created graph scope used by the graph retrieval route.
     */
    public GraphRequestContext graphRequestContext() {
        Object value = data.get(KEY_GRAPH_REQUEST_CONTEXT);
        if (!(value instanceof Map<?, ?> graphData)) {
            return null;
        }
        return new GraphRequestContext(
                textValue(graphData.get("graphId")),
                textValue(graphData.get("schemaId")),
                stringSet(graphData.get("subjectIds")),
                stringSet(graphData.get("allowedQueryIds"))
        );
    }

    /** Parse the server-created knowledge collection and optional document scope. */
    public KnowledgeRequestContext knowledgeRequestContext() {
        Object value = data.get(KEY_KNOWLEDGE_REQUEST_CONTEXT);
        if (!(value instanceof Map<?, ?> knowledgeData)) {
            return null;
        }
        return new KnowledgeRequestContext(
                textValue(knowledgeData.get("collection")),
                stringSet(knowledgeData.get("allowedDocumentIds"))
        );
    }

    /**
     * 是否联网搜索。
     * <ul>
     *   <li>{@code null} — 未指定，回退到环境变量或 GapAnalyzer 判定</li>
     *   <li>{@code true} — 显式启用联网搜索</li>
     *   <li>{@code false} — 显式禁用联网搜索</li>
     * </ul>
     * context JSON: {"needsWebSearch": true}
     */
    public Boolean needsWebSearch() {
        Object val = data.get(KEY_NEEDS_WEB_SEARCH);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return Boolean.parseBoolean(s);
        return null;
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
        copy.remove(KEY_GRAPH_REQUEST_CONTEXT);
        copy.remove(KEY_KNOWLEDGE_REQUEST_CONTEXT);
        copy.remove(KEY_NEEDS_GRAPH_KNOWLEDGE);
        return new AgentContext(copy);
    }

    private static String textValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static Set<String> stringSet(Object value) {
        if (!(value instanceof Iterable<?> values)) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object item : values) {
            if (item != null) {
                result.add(item.toString());
            }
        }
        return Set.copyOf(result);
    }
}

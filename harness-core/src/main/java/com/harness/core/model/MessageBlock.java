package com.harness.core.model;

import java.util.List;
import java.util.Map;

/**
 * A block within a message representing either text or an artifact reference.
 * Used to preserve the relative ordering of text and artifacts across page refreshes.
 */
public record MessageBlock(
        BlockType type,
        String text,         // non-null for TEXT blocks
        String artifactId,   // non-null for ARTIFACT blocks
        Map<String, Object> metadata  // optional: artifact type, mimeType, name, etc.
) {
    public enum BlockType { TEXT, ARTIFACT }

    /** Convenience constructor without metadata. */
    public MessageBlock(BlockType type, String text, String artifactId) {
        this(type, text, artifactId, null);
    }

    /**
     * Serialize a list of blocks to a JSON string.
     */
    public static String toJson(List<MessageBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < blocks.size(); i++) {
            if (i > 0) sb.append(",");
            MessageBlock b = blocks.get(i);
            sb.append("{\"type\":\"").append(b.type().name()).append("\"");
            if (b.text() != null) {
                sb.append(",\"text\":\"").append(escapeJson(b.text())).append("\"");
            }
            if (b.artifactId() != null) {
                sb.append(",\"artifactId\":\"").append(escapeJson(b.artifactId())).append("\"");
            }
            if (b.metadata() != null && !b.metadata().isEmpty()) {
                sb.append(",\"metadata\":{");
                boolean first = true;
                for (Map.Entry<String, Object> e : b.metadata().entrySet()) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("\"").append(escapeJson(e.getKey())).append("\":");
                    Object v = e.getValue();
                    if (v == null) {
                        sb.append("null");
                    } else if (v instanceof Number || v instanceof Boolean) {
                        sb.append(v);
                    } else {
                        sb.append("\"").append(escapeJson(v.toString())).append("\"");
                    }
                }
                sb.append("}");
            }
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Deserialize a JSON string to a list of blocks.
     * Returns null if input is null or empty.
     */
    public static List<MessageBlock> fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode arr = mapper.readTree(json);
            if (!arr.isArray()) return null;
            List<MessageBlock> blocks = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode node : arr) {
                String typeStr = node.has("type") ? node.get("type").asText() : null;
                String text = node.has("text") ? node.get("text").asText() : null;
                String artifactId = node.has("artifactId") ? node.get("artifactId").asText() : null;
                Map<String, Object> metadata = null;
                if (node.has("metadata") && node.get("metadata").isObject()) {
                    metadata = mapper.convertValue(node.get("metadata"),
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                }
                if (typeStr != null) {
                    BlockType type = BlockType.valueOf(typeStr);
                    blocks.add(new MessageBlock(type, text, artifactId, metadata));
                }
            }
            return blocks.isEmpty() ? null : blocks;
        } catch (Exception e) {
            return null;
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}

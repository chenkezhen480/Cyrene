package com.harness.core.model;

import java.util.List;
import java.util.Map;

/**
 * A block within a message representing text, an artifact reference, or structured data.
 * Used to preserve block ordering across page refreshes.
 */
public record MessageBlock(
        BlockType type,
        String text,         // non-null for TEXT blocks
        String artifactId,   // non-null for ARTIFACT blocks
        Map<String, Object> metadata  // optional: artifact type, mimeType, name, etc.
) {
    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .setSerializationInclusion(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

    public enum BlockType { TEXT, ARTIFACT, STRUCTURED_DATA }

    /** Convenience constructor without metadata. */
    public MessageBlock(BlockType type, String text, String artifactId) {
        this(type, text, artifactId, null);
    }

    /**
     * Serialize a list of blocks to a JSON string.
     */
    public static String toJson(List<MessageBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return null;
        try {
            return OBJECT_MAPPER.writeValueAsString(blocks);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Message blocks cannot be serialized", e);
        }
    }

    /**
     * Deserialize a JSON string to a list of blocks.
     * Returns null if input is null or empty.
     */
    public static List<MessageBlock> fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode arr = OBJECT_MAPPER.readTree(json);
            if (!arr.isArray()) return null;
            List<MessageBlock> blocks = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode node : arr) {
                String typeStr = node.has("type") ? node.get("type").asText() : null;
                String text = node.hasNonNull("text") ? node.get("text").asText() : null;
                String artifactId = node.hasNonNull("artifactId")
                        ? node.get("artifactId").asText()
                        : null;
                Map<String, Object> metadata = null;
                if (node.has("metadata") && node.get("metadata").isObject()) {
                    metadata = OBJECT_MAPPER.convertValue(node.get("metadata"),
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

}

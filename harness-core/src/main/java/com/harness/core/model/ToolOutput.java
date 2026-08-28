package com.harness.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider-neutral output produced by a Tool.
 *
 * <p>All three channels are optional and independent:</p>
 * <ul>
 *     <li>{@code text}: content returned to the model as the Tool result</li>
 *     <li>{@code artifacts}: durable files rendered as artifact blocks</li>
 *     <li>{@code json}: additional structured data rendered as a JSON block</li>
 * </ul>
 */
public record ToolOutput(
        String text,
        List<Artifact> artifacts,
        JsonNode json
) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public ToolOutput {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        json = json == null ? null : json.deepCopy();
    }

    public static ToolOutput empty() {
        return new ToolOutput(null, List.of(), null);
    }

    public static ToolOutput text(String text) {
        return new ToolOutput(text, List.of(), null);
    }

    public static ToolOutput json(JsonNode json) {
        return new ToolOutput(null, List.of(), json);
    }

    public static ToolOutput artifacts(String text, List<Artifact> artifacts) {
        return new ToolOutput(text, artifacts, null);
    }

    public boolean isEmpty() {
        return text == null && artifacts.isEmpty() && json == null;
    }

    /**
     * Content sent back to the model for the Tool execution result.
     * Single-channel outputs retain their natural representation. Combined outputs use one
     * deterministic JSON object so no channel is lost.
     */
    public String modelContent() {
        if (artifacts.isEmpty() && json == null) {
            return text == null ? "" : text;
        }
        if (text == null && artifacts.isEmpty()) {
            return json.toString();
        }
        try {
            ObjectNode root = OBJECT_MAPPER.createObjectNode();
            if (text != null) {
                root.put("text", text);
            }
            if (!artifacts.isEmpty()) {
                ArrayNode artifactNodes = root.putArray("artifacts");
                for (Artifact artifact : artifacts) {
                    artifactNodes.add(OBJECT_MAPPER.valueToTree(artifactMetadata(artifact)));
                }
            }
            if (json != null) {
                root.set("json", json);
            }
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Tool output cannot be serialized", e);
        }
    }

    /** Convert the output into ordered, persistable user-facing message blocks. */
    public List<MessageBlock> toMessageBlocks() {
        List<MessageBlock> blocks = new ArrayList<>();
        if (text != null) {
            blocks.add(new MessageBlock(MessageBlock.BlockType.TEXT, text, null));
        }
        for (Artifact artifact : artifacts) {
            blocks.add(new MessageBlock(
                    MessageBlock.BlockType.ARTIFACT,
                    null,
                    artifact.id(),
                    artifactMetadata(artifact)));
        }
        if (json != null) {
            blocks.add(new MessageBlock(
                    MessageBlock.BlockType.STRUCTURED_DATA,
                    null,
                    null,
                    Map.of("data", json.deepCopy())));
        }
        return List.copyOf(blocks);
    }

    /** Rebuild a Tool output from persisted text/artifact/structured-data message blocks. */
    public static ToolOutput fromMessageBlocks(List<MessageBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return empty();
        }
        StringBuilder text = new StringBuilder();
        List<Artifact> artifacts = new ArrayList<>();
        JsonNode json = null;
        for (MessageBlock block : blocks) {
            if (block == null || block.type() == null) {
                continue;
            }
            switch (block.type()) {
                case TEXT -> {
                    if (block.text() != null) {
                        text.append(block.text());
                    }
                }
                case ARTIFACT -> artifacts.add(toArtifact(block));
                case STRUCTURED_DATA -> {
                    if (block.metadata() != null && block.metadata().containsKey("data")) {
                        json = OBJECT_MAPPER.valueToTree(block.metadata().get("data"));
                    }
                }
            }
        }
        return new ToolOutput(text.isEmpty() ? null : text.toString(), artifacts, json);
    }

    private static Map<String, Object> artifactMetadata(Artifact artifact) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("id", artifact.id());
        metadata.put("type", artifact.type() != null ? artifact.type().name() : null);
        metadata.put("mimeType", artifact.mimeType());
        metadata.put("name", artifact.name());
        metadata.put("sizeBytes", artifact.sizeBytes());
        metadata.put("downloadUrl", artifact.downloadUrl());
        metadata.values().removeIf(java.util.Objects::isNull);
        return Map.copyOf(metadata);
    }

    private static Artifact toArtifact(MessageBlock block) {
        Map<String, Object> metadata = block.metadata() == null ? Map.of() : block.metadata();
        String mimeType = stringValue(metadata.get("mimeType"));
        String typeValue = stringValue(metadata.get("type"));
        Artifact.ArtifactType type;
        try {
            type = typeValue == null
                    ? Artifact.inferType(mimeType)
                    : Artifact.ArtifactType.valueOf(typeValue);
        } catch (IllegalArgumentException e) {
            type = Artifact.inferType(mimeType);
        }
        return new Artifact(
                block.artifactId(),
                null,
                stringValue(metadata.getOrDefault("name", "artifact")),
                type,
                mimeType,
                longValue(metadata.get("sizeBytes")),
                "",
                Instant.EPOCH);
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

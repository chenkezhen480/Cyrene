package com.harness.tool.rag;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class KnowledgeChunkCursorCodec {

    private static final int VERSION = 1;
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private KnowledgeChunkCursorCodec() {
    }

    static String encode(String collection, String fileName, String lastId) {
        validateScope(collection, lastId);
        try {
            byte[] json = JSON_MAPPER.writeValueAsBytes(new CursorPayload(
                    VERSION, collection, normalizeFileName(fileName), lastId));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode knowledge page cursor", e);
        }
    }

    static String decodeLastId(String cursor, String collection, String fileName) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            byte[] json = Base64.getUrlDecoder().decode(cursor);
            CursorPayload payload = JSON_MAPPER.readValue(
                    new String(json, StandardCharsets.UTF_8), CursorPayload.class);
            if (payload.version() != VERSION
                    || !collection.equals(payload.collection())
                    || !normalizeFileName(fileName).equals(payload.fileName())
                    || payload.lastId() == null
                    || payload.lastId().isBlank()) {
                throw new IllegalArgumentException("Cursor does not match the current knowledge query");
            }
            return payload.lastId();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid knowledge page cursor", e);
        }
    }

    static String encodeCollection(String lastCollection) {
        if (lastCollection == null || lastCollection.isBlank()) {
            throw new IllegalArgumentException("lastCollection is required");
        }
        try {
            byte[] json = JSON_MAPPER.writeValueAsBytes(new CollectionCursorPayload(
                    VERSION, lastCollection));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode knowledge collection cursor", e);
        }
    }

    static String decodeLastCollection(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            byte[] json = Base64.getUrlDecoder().decode(cursor);
            CollectionCursorPayload payload = JSON_MAPPER.readValue(
                    new String(json, StandardCharsets.UTF_8), CollectionCursorPayload.class);
            if (payload.version() != VERSION
                    || payload.lastCollection() == null
                    || payload.lastCollection().isBlank()) {
                throw new IllegalArgumentException("Invalid knowledge collection cursor");
            }
            return payload.lastCollection();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid knowledge collection cursor", e);
        }
    }

    static String normalizeFileName(String fileName) {
        return fileName == null ? "" : fileName.trim();
    }

    private static void validateScope(String collection, String lastId) {
        if (collection == null || collection.isBlank()) {
            throw new IllegalArgumentException("collection is required");
        }
        if (lastId == null || lastId.isBlank()) {
            throw new IllegalArgumentException("lastId is required");
        }
    }

    private record CursorPayload(
            int version,
            String collection,
            String fileName,
            String lastId
    ) {
    }

    private record CollectionCursorPayload(int version, String lastCollection) {
    }
}

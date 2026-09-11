package com.harness.server;

import com.harness.core.model.PageResponse;
import com.harness.tool.knowledge.KnowledgeChunkSummary;
import com.harness.tool.knowledge.KnowledgeDocumentLifecycleService;
import com.harness.tool.rag.VectorStore;
import com.harness.server.api.ApiErrorCode;
import com.harness.server.api.ApiResponses;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;

/**
 * Handler for knowledge base management endpoints:
 *   GET    /api/knowledge/{collection}                  - List documents in a collection
 *   DELETE /api/knowledge/{collection}                  - Delete all documents in a collection
 *   DELETE /api/knowledge/{collection}/{documentId}     - Delete a specific document
 */
public class KnowledgeManagementHandler {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeManagementHandler.class);
    private static final int DEFAULT_PAGE_LIMIT = 50;
    private static final int MAX_PAGE_LIMIT = 100;
    private static final int MAX_FILE_NAME_LENGTH = 512;

    private final VectorStore vectorStore;
    private final KnowledgeDocumentLifecycleService lifecycleService;

    public KnowledgeManagementHandler(VectorStore vectorStore) {
        this(vectorStore, null);
    }

    public KnowledgeManagementHandler(
            VectorStore vectorStore,
            KnowledgeDocumentLifecycleService lifecycleService
    ) {
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore");
        this.lifecycleService = lifecycleService;
    }

    /**
     * GET /api/knowledge/{collection} — list documents in a collection.
     */
    public void listDocuments(Context ctx) {
        String collection = ctx.pathParam("collection");
        if (collection == null || collection.isBlank()) {
            ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST,
                    "Collection name is required");
            return;
        }

        try {
            String fileName = normalizeFileName(ctx.queryParam("fileName"));
            if (fileName.length() > MAX_FILE_NAME_LENGTH) {
                throw new IllegalArgumentException(
                        "fileName must not exceed " + MAX_FILE_NAME_LENGTH + " characters");
            }
            int limit = parseLimit(ctx.queryParam("limit"));
            String cursor = ctx.queryParam("cursor");
            PageResponse<KnowledgeChunkSummary> page = vectorStore.listKnowledgeChunks(
                    collection, fileName, limit, cursor);
            ctx.json(page);
        } catch (IllegalArgumentException e) {
            ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("[Server] Failed to list documents for collection '{}': {}", collection, e.getMessage(), e);
            ApiResponses.error(ctx, 500, ApiErrorCode.INTERNAL_ERROR,
                    "Failed to list knowledge chunks");
        }
    }

    public void listCollections(Context ctx) {
        try {
            int limit = parseLimit(ctx.queryParam("limit"));
            ctx.json(vectorStore.listCollections(limit, ctx.queryParam("cursor")));
        } catch (IllegalArgumentException e) {
            ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("[Server] Failed to list knowledge collections: {}", e.getMessage(), e);
            ApiResponses.error(ctx, 500, ApiErrorCode.INTERNAL_ERROR,
                    "Failed to list knowledge collections");
        }
    }

    /**
     * DELETE /api/knowledge/{collection} — delete all documents in a collection.
     */
    public void deleteCollection(Context ctx) {
        ApiResponses.error(ctx, 405, ApiErrorCode.INVALID_REQUEST,
                "Direct Collection deletion is disabled; deprecate Source Documents explicitly");
    }

    /**
     * GET /api/knowledge/{collection}/{documentId} — get a single document with content.
     */
    public void getDocument(Context ctx) {
        String collection = ctx.pathParam("collection");
        String documentId = ctx.pathParam("documentId");
        if (collection == null || collection.isBlank()
                || documentId == null || documentId.isBlank()) {
            ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST,
                    "Collection name and document ID are required");
            return;
        }

        try {
            VectorStore.Document doc = vectorStore.getById(collection, documentId);
            if (doc != null) {
                ctx.json(doc);
            } else {
                ApiResponses.error(
                        ctx, 404, ApiErrorCode.NOT_FOUND, "Document not found: " + documentId);
            }
        } catch (IllegalArgumentException e) {
            ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("[Server] Failed to get document '{}': {}", documentId, e.getMessage(), e);
            ApiResponses.error(ctx, 500, ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    /**
     * PUT /api/knowledge/{collection}/{documentId} — update a document's content.
     */
    public void updateDocument(Context ctx) {
        ApiResponses.error(ctx, 405, ApiErrorCode.INVALID_REQUEST,
                "Chunk editing is disabled; upload a complete Source Document Revision");
    }

    /**
     * DELETE /api/knowledge/{collection}/{documentId} — delete a specific document.
     */
    public void deleteDocument(Context ctx) {
        if (lifecycleService == null) {
            ApiResponses.error(ctx, 405, ApiErrorCode.INVALID_REQUEST,
                    "Chunk deletion is disabled; deprecate the Source Document Concept");
            return;
        }
        try {
            ctx.json(lifecycleService.deprecate(
                    ctx.pathParam("collection"), ctx.pathParam("documentId"), null));
        } catch (IllegalArgumentException e) {
            ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("[Server] Failed to deprecate Source Document: {}", e.getMessage(), e);
            ApiResponses.error(ctx, 500, ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    private static int parseLimit(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_PAGE_LIMIT;
        }
        try {
            int limit = Integer.parseInt(value);
            if (limit <= 0 || limit > MAX_PAGE_LIMIT) {
                throw new IllegalArgumentException(
                        "limit must be between 1 and " + MAX_PAGE_LIMIT);
            }
            return limit;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("limit must be an integer", e);
        }
    }

    private static String normalizeFileName(String fileName) {
        return fileName == null ? "" : fileName.trim();
    }
}

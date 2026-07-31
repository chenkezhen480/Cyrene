package com.harness.server;

import com.harness.preprocess.knowledge.FileStorageService;
import com.harness.preprocess.rag.VectorStore;
import com.harness.server.api.ApiErrorCode;
import com.harness.server.api.ApiResponses;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Handler for knowledge base management endpoints:
 *   GET    /api/knowledge/{collection}                  - List documents in a collection
 *   DELETE /api/knowledge/{collection}                  - Delete all documents in a collection
 *   DELETE /api/knowledge/{collection}/{documentId}     - Delete a specific document
 */
public class KnowledgeManagementHandler {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeManagementHandler.class);

    private final VectorStore vectorStore;
    private final FileStorageService fileStorage;

    public KnowledgeManagementHandler(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        this.fileStorage = new FileStorageService();
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
            List<VectorStore.Document> docs = vectorStore.listByCollection(collection);
            ctx.json(Map.of(
                    "collection", collection,
                    "count", docs.size(),
                    "documents", docs
            ));
        } catch (Exception e) {
            log.error("[Server] Failed to list documents for collection '{}': {}", collection, e.getMessage(), e);
            ApiResponses.error(ctx, 500, ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    /**
     * DELETE /api/knowledge/{collection} — delete all documents in a collection.
     */
    public void deleteCollection(Context ctx) {
        String collection = ctx.pathParam("collection");
        if (collection == null || collection.isBlank()) {
            ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST,
                    "Collection name is required");
            return;
        }

        try {
            vectorStore.delete(collection);

            // Also try to delete the file storage directory for this collection
            try {
                fileStorage.delete(collection);
            } catch (Exception e) {
                log.debug("[Server] File storage cleanup for collection '{}' skipped: {}", collection, e.getMessage());
            }

            log.info("[Server] Deleted collection '{}'", collection);
            ctx.json(Map.of(
                    "collection", collection,
                    "deletedCount", -1
            ));
        } catch (Exception e) {
            log.error("[Server] Failed to delete collection '{}': {}", collection, e.getMessage(), e);
            ApiResponses.error(ctx, 500, ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    /**
     * GET /api/knowledge/{collection}/{documentId} — get a single document with content.
     */
    public void getDocument(Context ctx) {
        String documentId = ctx.pathParam("documentId");
        if (documentId == null || documentId.isBlank()) {
            ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST, "Document ID is required");
            return;
        }

        try {
            VectorStore.Document doc = vectorStore.fetchById(documentId);
            if (doc != null) {
                ctx.json(doc);
            } else {
                ApiResponses.error(
                        ctx, 404, ApiErrorCode.NOT_FOUND, "Document not found: " + documentId);
            }
        } catch (Exception e) {
            log.error("[Server] Failed to get document '{}': {}", documentId, e.getMessage(), e);
            ApiResponses.error(ctx, 500, ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    /**
     * PUT /api/knowledge/{collection}/{documentId} — update a document's content.
     */
    public void updateDocument(Context ctx) {
        String documentId = ctx.pathParam("documentId");
        String collection = ctx.pathParam("collection");
        if (documentId == null || documentId.isBlank()) {
            ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST, "Document ID is required");
            return;
        }

        try {
            var body = ctx.bodyAsClass(java.util.Map.class);
            String content = (String) body.get("content");
            if (content == null || content.isBlank()) {
                ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST, "Content is required");
                return;
            }

            // Get existing doc to preserve metadata
            VectorStore.Document existing = vectorStore.fetchById(documentId);
            if (existing == null) {
                ApiResponses.error(
                        ctx, 404, ApiErrorCode.NOT_FOUND, "Document not found: " + documentId);
                return;
            }

            // Re-embed and update
            // Note: requires embedding provider — for now update content only
            VectorStore.Document updated = new VectorStore.Document(
                    documentId, content, existing.source(), 0,
                    existing.metadata(), existing.embedding(), existing.chunkIndex());
            vectorStore.upsert(collection, List.of(updated));

            log.info("[Server] Updated document {} in collection '{}'", documentId, collection);
            ctx.json(Map.of("documentId", documentId, "updated", true));
        } catch (Exception e) {
            log.error("[Server] Failed to update document '{}': {}", documentId, e.getMessage(), e);
            ApiResponses.error(ctx, 500, ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    /**
     * DELETE /api/knowledge/{collection}/{documentId} — delete a specific document.
     */
    public void deleteDocument(Context ctx) {
        String collection = ctx.pathParam("collection");
        String documentId = ctx.pathParam("documentId");
        if (collection == null || collection.isBlank() || documentId == null || documentId.isBlank()) {
            ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST,
                    "Collection name and document ID are required");
            return;
        }

        try {
            boolean deleted = vectorStore.deleteById(documentId);
            if (deleted) {
                log.info("[Server] Deleted document {} from collection '{}'", documentId, collection);
                ctx.json(Map.of(
                        "collection", collection,
                        "documentId", documentId,
                        "deleted", true
                ));
            } else {
                ApiResponses.error(ctx, 404, ApiErrorCode.NOT_FOUND, "Document not found",
                        Map.of("collection", collection, "documentId", documentId));
            }
        } catch (Exception e) {
            log.error("[Server] Failed to delete document {} from collection '{}': {}", documentId, collection, e.getMessage(), e);
            ApiResponses.error(ctx, 500, ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }
}

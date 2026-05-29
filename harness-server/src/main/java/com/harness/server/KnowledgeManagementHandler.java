package com.harness.server;

import com.harness.preprocess.knowledge.FileStorageService;
import com.harness.preprocess.rag.PgVectorRagRetriever;
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

    private final PgVectorRagRetriever pgVector;
    private final FileStorageService fileStorage;

    public KnowledgeManagementHandler(PgVectorRagRetriever pgVector) {
        this.pgVector = pgVector;
        this.fileStorage = new FileStorageService();
    }

    /**
     * GET /api/knowledge/{collection} — list documents in a collection.
     */
    public void listDocuments(Context ctx) {
        String collection = ctx.pathParam("collection");
        if (collection == null || collection.isBlank()) {
            ctx.status(400).json(Map.of("error", "Collection name is required"));
            return;
        }

        try {
            List<PgVectorRagRetriever.RagDocumentSummary> docs = pgVector.listByCollection(collection);
            ctx.json(Map.of(
                    "collection", collection,
                    "count", docs.size(),
                    "documents", docs
            ));
        } catch (Exception e) {
            log.error("[Server] Failed to list documents for collection '{}': {}", collection, e.getMessage(), e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    /**
     * DELETE /api/knowledge/{collection} — delete all documents in a collection.
     */
    public void deleteCollection(Context ctx) {
        String collection = ctx.pathParam("collection");
        if (collection == null || collection.isBlank()) {
            ctx.status(400).json(Map.of("error", "Collection name is required"));
            return;
        }

        try {
            int deleted = pgVector.deleteByCollection(collection);

            // Also try to delete the file storage directory for this collection
            try {
                fileStorage.delete(collection);
            } catch (Exception e) {
                log.debug("[Server] File storage cleanup for collection '{}' skipped: {}", collection, e.getMessage());
            }

            log.info("[Server] Deleted collection '{}': {} documents removed", collection, deleted);
            ctx.json(Map.of(
                    "collection", collection,
                    "deletedCount", deleted
            ));
        } catch (Exception e) {
            log.error("[Server] Failed to delete collection '{}': {}", collection, e.getMessage(), e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    /**
     * DELETE /api/knowledge/{collection}/{documentId} — delete a specific document.
     */
    public void deleteDocument(Context ctx) {
        String collection = ctx.pathParam("collection");
        String documentId = ctx.pathParam("documentId");
        if (collection == null || collection.isBlank() || documentId == null || documentId.isBlank()) {
            ctx.status(400).json(Map.of("error", "Collection name and document ID are required"));
            return;
        }

        try {
            boolean deleted = pgVector.deleteById(documentId);
            if (deleted) {
                log.info("[Server] Deleted document {} from collection '{}'", documentId, collection);
                ctx.json(Map.of(
                        "collection", collection,
                        "documentId", documentId,
                        "deleted", true
                ));
            } else {
                ctx.status(404).json(Map.of(
                        "error", "Document not found",
                        "collection", collection,
                        "documentId", documentId
                ));
            }
        } catch (Exception e) {
            log.error("[Server] Failed to delete document {} from collection '{}': {}", documentId, collection, e.getMessage(), e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }
}

package com.harness.server;

import com.harness.audit.store.TraceStore;
import com.harness.core.model.AgentTrace;
import com.harness.core.model.RiskLevel;
import com.harness.preprocess.knowledge.IngestResult;
import com.harness.preprocess.knowledge.KnowledgeIngestService;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KnowledgeUploadHandler {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeUploadHandler.class);
    private final KnowledgeIngestService ingestService;
    private final TraceStore traceStore;

    public KnowledgeUploadHandler(KnowledgeIngestService ingestService, TraceStore traceStore) {
        this.ingestService = ingestService;
        this.traceStore = traceStore;
    }

    public void handle(Context ctx) {
        try {
            UploadedFile uploadedFile = ctx.uploadedFile("file");
            if (uploadedFile == null) {
                log.warn("[Server] Knowledge upload: no file provided");
                ctx.status(400).json(Map.of("error", "No file uploaded"));
                return;
            }

            String collection = ctx.formParam("collection");
            byte[] fileData = uploadedFile.content().readAllBytes();
            String fileName;
            try {
                fileName = uploadedFile.filename();
            } catch (Exception e) {
                // Jetty may throw NotUtf8Exception for non-UTF-8 filenames (e.g., Chinese filenames
                // sent by some clients with GBK encoding). Fall back to a safe default.
                log.warn("[Server] Failed to decode uploaded filename ({}), using fallback", e.getMessage());
                fileName = ctx.formParam("file") != null ? ctx.formParam("file") : "uploaded_file_" + System.currentTimeMillis();
            }
            String mimeType = uploadedFile.contentType();
            log.debug("[Server] POST /api/knowledge/upload: file={}, size={}KB, mimeType={}, collection={}",
                    fileName, fileData.length / 1024, mimeType, collection);

            IngestResult result = ingestService.ingest(fileData, fileName, mimeType, collection);

            log.info("[Server] Knowledge ingested: chunks={}, embeddings={}, duration={}ms",
                    result.chunkCount(), result.embeddingDimension(), result.ingestDurationMs());

            // Record trace
            try {
                Map<String, String> meta = new HashMap<>();
                meta.put("type", "knowledge_upload");
                meta.put("file_name", result.fileName());
                meta.put("collection", result.collection());
                meta.put("chunk_count", String.valueOf(result.chunkCount()));
                meta.put("embedding_dim", String.valueOf(result.embeddingDimension()));
                meta.put("stored_path", result.storedFilePath());

                AgentTrace trace = AgentTrace.builder()
                        .inputText("knowledge upload: " + fileName)
                        .finalOutput("Ingested " + result.chunkCount() + " chunks")
                        .riskLevel(RiskLevel.LOW)
                        .totalDurationMs(result.ingestDurationMs())
                        .metadata(meta)
                        .build();
                traceStore.save(trace);
            } catch (Exception e) {
                log.warn("[Server] Failed to save knowledge trace: {}", e.getMessage());
            }

            ctx.json(Map.of(
                    "fileName", result.fileName(),
                    "collection", result.collection(),
                    "chunkCount", result.chunkCount(),
                    "embeddingDimension", result.embeddingDimension(),
                    "storedPath", result.storedFilePath(),
                    "ingestDurationMs", result.ingestDurationMs()
            ));

        } catch (IllegalArgumentException e) {
            log.warn("[Server] Knowledge upload validation failed: {}", e.getMessage());
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[Server] Knowledge upload failed: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", e.getMessage() != null ? e.getMessage() : "Internal server error during knowledge upload"));
        }
    }
}

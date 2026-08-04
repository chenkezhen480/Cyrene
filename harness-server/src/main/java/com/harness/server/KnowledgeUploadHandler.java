package com.harness.server;

import com.harness.trace.store.TraceStore;
import com.harness.core.model.AgentTrace;
import com.harness.core.model.RiskLevel;
import com.harness.tool.knowledge.IngestResult;
import com.harness.tool.knowledge.KnowledgeIngestService;
import com.harness.server.api.ApiErrorCode;
import com.harness.server.api.ApiResponses;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class KnowledgeUploadHandler {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeUploadHandler.class);

    // Only allow document types that TextExtractorRegistry can actually parse
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "pptx", "csv", "json", "xml",
            "rtf", "odt", "ods", "txt", "md"
    );

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
                ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST, "No file uploaded");
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

            // Validate file extension
            String ext = getExtension(fileName).toLowerCase();
            if (!ext.isEmpty() && !ALLOWED_EXTENSIONS.contains(ext)) {
                ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST,
                        "File type not allowed for knowledge base: ." + ext);
                return;
            }

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
                meta.put("repaired_block_count", String.valueOf(result.repairedBlockCount()));
                if (!result.repairModel().isBlank()) {
                    meta.put("repair_model", result.repairModel());
                }

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
                    "repairedBlockCount", result.repairedBlockCount(),
                    "repairModel", result.repairModel(),
                    "storedPath", result.storedFilePath(),
                    "ingestDurationMs", result.ingestDurationMs()
            ));

        } catch (IllegalArgumentException e) {
            log.warn("[Server] Knowledge upload validation failed: {}", e.getMessage());
            ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("[Server] Knowledge upload failed: {}", e.getMessage(), e);
            ApiResponses.error(ctx, 500, ApiErrorCode.INTERNAL_ERROR,
                    e.getMessage() != null
                            ? e.getMessage()
                            : "Internal server error during knowledge upload");
        }
    }

    private String getExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1) : "";
    }
}

package com.harness.server;

import com.harness.trace.store.TraceStore;
import com.harness.core.model.AgentTrace;
import com.harness.core.model.RiskLevel;
import com.harness.tool.knowledge.IngestResult;
import com.harness.tool.knowledge.KnowledgeIngestService;
import com.harness.input.document.DocumentConversionException;
import com.harness.server.api.ApiErrorCode;
import com.harness.server.api.ApiResponses;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
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
                meta.put("document_converter", result.documentConverter());
                meta.put("detected_mime_type", result.detectedMimeType());
                meta.put("document_ocr_enabled", String.valueOf(result.ocrEnabled()));
                meta.put("document_vision_calls", String.valueOf(result.visionCalls()));
                meta.put("document_vision_source", result.visionSource());
                meta.put("document_conversion_duration_ms",
                        String.valueOf(result.conversionDurationMs()));
                if (result.visionModel() != null) {
                    meta.put("document_vision_model", result.visionModel());
                }
                if (!result.conversionWarnings().isEmpty()) {
                    meta.put("document_conversion_warnings",
                            String.join("\n", result.conversionWarnings()));
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

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("fileName", result.fileName());
            response.put("collection", result.collection());
            response.put("chunkCount", result.chunkCount());
            response.put("embeddingDimension", result.embeddingDimension());
            response.put("documentConverter", result.documentConverter());
            response.put("detectedMimeType", result.detectedMimeType());
            response.put("visionModel", result.visionModel());
            response.put("visionSource", result.visionSource());
            response.put("ocrEnabled", result.ocrEnabled());
            response.put("visionCalls", result.visionCalls());
            response.put("conversionDurationMs", result.conversionDurationMs());
            response.put("conversionWarnings", result.conversionWarnings());
            response.put("storedPath", result.storedFilePath());
            response.put("ingestDurationMs", result.ingestDurationMs());
            ctx.json(response);

        } catch (DocumentConversionException e) {
            int workerStatus = e.statusCode();
            int status = workerStatus == 400 || workerStatus == 413
                    || workerStatus == 415 || workerStatus == 422
                    ? 400
                    : 503;
            log.warn("[Server] Knowledge document conversion failed: {}", e.getMessage());
            ApiResponses.error(ctx, status, ApiErrorCode.fromHttpStatus(status), e.getMessage());
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

}

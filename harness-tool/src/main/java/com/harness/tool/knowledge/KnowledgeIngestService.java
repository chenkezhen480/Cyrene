package com.harness.tool.knowledge;

import com.harness.provider.EmbeddingModelProvider;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.modelconfig.ModelConfigKey;
import com.harness.input.document.DocumentConversionDiagnostics;
import com.harness.input.document.DocumentConversionResult;
import com.harness.input.document.DocumentConversionService;
import com.harness.input.multimodal.MarkdownChunk;
import com.harness.input.multimodal.TextChunker;
import com.harness.tool.rag.VectorStore;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class KnowledgeIngestService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestService.class);

    private final EmbeddingModelProvider embeddingProvider;
    private final VectorStore vectorStore;
    private final FileStorageService fileStorage;
    private final DocumentConversionService documentConversionService;
    private final TextChunker textChunker;
    private final String defaultCollection;
    private final long maxFileSizeMb;

    public KnowledgeIngestService(
            EmbeddingModelProvider embeddingProvider,
            VectorStore vectorStore,
            DocumentConversionService documentConversionService,
            FileStorageService fileStorage
    ) {
        this.embeddingProvider = java.util.Objects.requireNonNull(
                embeddingProvider, "embeddingProvider");
        this.vectorStore = java.util.Objects.requireNonNull(vectorStore, "vectorStore");
        this.documentConversionService = java.util.Objects.requireNonNull(
                documentConversionService, "documentConversionService");
        this.fileStorage = java.util.Objects.requireNonNull(fileStorage, "fileStorage");
        this.textChunker = new TextChunker(embeddingProvider.tokenEstimator());

        EnvConfig cfg = EnvConfig.get();
        this.defaultCollection = cfg.getString(EnvKey.RAG_COLLECTION, "default");
        this.maxFileSizeMb = cfg.getLong(EnvKey.KNOWLEDGE_MAX_FILE_SIZE_MB, 50);
    }

    public IngestResult ingest(byte[] fileData, String fileName, String mimeType, String collection) {
        long startTime = System.currentTimeMillis();
        String coll = (collection != null && !collection.isBlank()) ? collection : defaultCollection;

        // Pre-flight: embedding provider must be available
        if (!embeddingProvider.isAvailable()) {
            throw new IllegalStateException("Embedding model not configured. Set "
                    + ModelConfigKey.EMBEDDING_PROVIDER + " in model.conf.");
        }

        // Validate file size
        long maxFileBytes = Math.multiplyExact(maxFileSizeMb, 1024L * 1024L);
        if (fileData.length > maxFileBytes) {
            throw new IllegalArgumentException(
                    "File size exceeds limit " + maxFileSizeMb + "MB");
        }

        log.debug("Starting ingest: file={}, size={}KB, mimeType={}, collection={}", fileName, fileData.length / 1024, mimeType, coll);

        // Step 1: Convert every document through the shared MarkItDown boundary.
        DocumentConversionResult convertedDocument = documentConversionService.convert(
                fileData, fileName, mimeType);
        validateDocumentTypeEnabled(convertedDocument.detectedMimeType());
        String rawText = convertedDocument.markdown();
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException(
                    "No Markdown content converted from file: " + fileName);
        }
        DocumentConversionDiagnostics conversion = convertedDocument.diagnostics();

        // Step 2: Parse Markdown blocks and pack adjacent blocks in one budget-aware pass.
        int chunkSize = EnvConfig.get().getInt(EnvKey.KNOWLEDGE_CHUNK_SIZE, 1024);
        List<MarkdownChunk> chunks = textChunker.chunk(rawText, chunkSize);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException(
                    "No retrievable Markdown content converted from file: " + fileName);
        }

        // Step 3: Generate embeddings (batched to avoid API body size limits)
        List<TextSegment> segments = chunks.stream()
                .map(MarkdownChunk::content)
                .map(TextSegment::from)
                .toList();
        int batchSize = 10;
        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < segments.size(); i += batchSize) {
            int end = Math.min(i + batchSize, segments.size());
            List<TextSegment> batch = segments.subList(i, end);

            embeddings.addAll(embeddingProvider.embedAll(batch));
        }
        if (embeddings.size() != chunks.size()) {
            throw new IllegalStateException("Embedding count mismatch: expected " + chunks.size()
                    + " but got " + embeddings.size());
        }

        // Step 4: Store original file to disk
        String storedPath = fileStorage.store(fileData, fileName, coll);

        // Step 5: Build documents and upsert via VectorStore
        String documentId = UUID.randomUUID().toString();
        List<VectorStore.Document> docs = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            MarkdownChunk chunk = chunks.get(i);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("document_id", documentId);
            metadata.put("file_name", fileName);
            metadata.put("chunk_index", i);
            metadata.put("total_chunks", chunks.size());
            metadata.put("start_block_index", chunk.startBlockIndex());
            metadata.put("end_block_index", chunk.endBlockIndex());
            metadata.put("heading_path", chunk.headingPath());
            metadata.put("token_count", chunk.tokenCount());
            metadata.put("token_estimator", textChunker.tokenEstimatorStrategy());
            metadata.put("document_converter", conversion.converter());
            metadata.put("document_mime_type", convertedDocument.detectedMimeType());
            metadata.put("document_ocr_enabled", conversion.ocrEnabled());
            metadata.put("document_vision_calls", conversion.visionCalls());
            metadata.put("document_vision_source", conversion.visionSource());
            if (conversion.model() != null) {
                metadata.put("document_vision_model", conversion.model());
            }
            if (!conversion.warnings().isEmpty()) {
                metadata.put("document_conversion_warnings",
                        String.join("\n", conversion.warnings()));
            }

            docs.add(new VectorStore.Document(
                    null,
                    chunk.content(),
                    fileName,
                    0,
                    metadata,
                    embeddings.get(i).vector(),
                    i
            ));
        }
        try {
            vectorStore.upsert(coll, docs);
        } catch (Exception e) {
            // Rollback: clean up the stored file since DB insert failed
            log.warn("[Ingest] DB insert failed, cleaning up stored file: {}", storedPath);
            fileStorage.delete(storedPath);
            throw e;
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Ingest complete: {} chunks in {}ms using {}", chunks.size(), duration,
                textChunker.tokenEstimatorStrategy());

        return new IngestResult(
                fileName,
                coll,
                chunks.size(),
                embeddingProvider.dimension(),
                storedPath,
                duration,
                conversion.converter(),
                convertedDocument.detectedMimeType(),
                conversion.model(),
                conversion.visionSource(),
                conversion.ocrEnabled(),
                conversion.visionCalls(),
                conversion.elapsedMs(),
                conversion.warnings()
        );
    }

    private static void validateDocumentTypeEnabled(String mimeType) {
        EnvConfig config = EnvConfig.get();
        boolean enabled = switch (mimeType) {
            case "application/pdf" -> config.getBool(EnvKey.KNOWLEDGE_PDF_ENABLED, true);
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                 "application/msword" -> config.getBool(EnvKey.KNOWLEDGE_DOCX_ENABLED, true);
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                 "application/vnd.ms-excel" -> config.getBool(EnvKey.KNOWLEDGE_XLSX_ENABLED, true);
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" ->
                    config.getBool(EnvKey.KNOWLEDGE_PPTX_ENABLED, true);
            default -> true;
        };
        if (!enabled) {
            throw new IllegalArgumentException(
                    "Knowledge ingestion is disabled for MIME type: " + mimeType);
        }
    }

    public String getDefaultCollection() {
        return defaultCollection;
    }
}

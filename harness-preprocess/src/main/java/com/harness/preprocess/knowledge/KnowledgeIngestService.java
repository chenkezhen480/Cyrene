package com.harness.preprocess.knowledge;

import com.harness.ai.model.EmbeddingModelProvider;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.input.multimodal.TextChunker;
import com.harness.input.multimodal.impl.TextExtractorRegistry;
import com.harness.preprocess.rag.PgVectorRagRetriever;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KnowledgeIngestService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestService.class);

    private final EmbeddingModelProvider embeddingProvider;
    private final PgVectorRagRetriever pgVector;
    private final FileStorageService fileStorage;
    private final String defaultCollection;
    private final long maxFileSizeMb;

    public KnowledgeIngestService(EmbeddingModelProvider embeddingProvider, PgVectorRagRetriever pgVector) {
        this.embeddingProvider = embeddingProvider;
        this.pgVector = pgVector;
        this.fileStorage = new FileStorageService();

        EnvConfig cfg = EnvConfig.get();
        this.defaultCollection = cfg.getString(EnvKey.KNOWLEDGE_DEFAULT_COLLECTION, "default");
        this.maxFileSizeMb = cfg.getLong(EnvKey.KNOWLEDGE_MAX_FILE_SIZE_MB, 50);
    }

    public IngestResult ingest(byte[] fileData, String fileName, String mimeType, String collection) {
        long startTime = System.currentTimeMillis();
        String coll = (collection != null && !collection.isBlank()) ? collection : defaultCollection;

        // Pre-flight: embedding provider must be available
        if (!embeddingProvider.isAvailable()) {
            throw new IllegalStateException("Embedding model not configured. Set HARNESS_MODEL_EMBEDDING_PROVIDER.");
        }

        // Validate file size
        long sizeMb = fileData.length / (1024 * 1024);
        if (sizeMb > maxFileSizeMb) {
            throw new IllegalArgumentException("File size " + sizeMb + "MB exceeds limit " + maxFileSizeMb + "MB");
        }

        log.info("Starting ingest: file={}, size={}KB, mimeType={}, collection={}", fileName, fileData.length / 1024, mimeType, coll);

        // Step 1: Extract text from file
        String rawText = TextExtractorRegistry.extract(fileData, fileName, mimeType);
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("No text content extracted from file: " + fileName);
        }
        log.info("Extracted {} chars of text", rawText.length());

        // Step 2: Split into chunks
        int chunkSize = EnvConfig.get().getInt(EnvKey.KNOWLEDGE_CHUNK_SIZE, 1024);
        List<String> chunks = TextChunker.split(rawText, chunkSize);
        log.info("Split into {} chunks (chunkSize={})", chunks.size(), chunkSize);

        // Step 3: Generate embeddings (batched to avoid API body size limits)
        List<TextSegment> segments = chunks.stream()
                .map(TextSegment::from)
                .toList();
        int batchSize = 10;
        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < segments.size(); i += batchSize) {
            int end = Math.min(i + batchSize, segments.size());
            List<TextSegment> batch = segments.subList(i, end);
            log.info("Embedding batch {}/{}: chunks {}-{}", (i / batchSize) + 1,
                    (segments.size() + batchSize - 1) / batchSize, i, end - 1);
            embeddings.addAll(embeddingProvider.embedAll(batch));
        }
        if (embeddings.size() != chunks.size()) {
            throw new IllegalStateException("Embedding count mismatch: expected " + chunks.size()
                    + " but got " + embeddings.size());
        }
        log.info("Generated {} embeddings (dim={})", embeddings.size(), embeddingProvider.dimension());

        // Step 4: Store original file to disk
        String storedPath = fileStorage.store(fileData, fileName, coll);

        // Step 5: Build linked document entries and insert into pgvector
        List<PgVectorRagRetriever.DocumentLinkEntry> entries = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("file_name", fileName);
            metadata.put("chunk_index", i);
            metadata.put("total_chunks", chunks.size());

            entries.add(new PgVectorRagRetriever.DocumentLinkEntry(
                    chunks.get(i),
                    fileName,
                    embeddings.get(i).vector(),
                    coll,
                    i,
                    metadata
            ));
        }
        pgVector.insertBatchWithLinks(entries);

        long duration = System.currentTimeMillis() - startTime;
        log.info("Ingest complete: {} chunks in {}ms", chunks.size(), duration);

        return new IngestResult(
                fileName,
                coll,
                chunks.size(),
                embeddingProvider.dimension(),
                storedPath,
                duration
        );
    }

    public String getDefaultCollection() {
        return defaultCollection;
    }
}

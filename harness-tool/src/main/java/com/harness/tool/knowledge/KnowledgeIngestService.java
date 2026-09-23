package com.harness.tool.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.knowledge.*;
import com.harness.core.modelconfig.ModelConfigKey;
import com.harness.input.document.DocumentConversionException;
import com.harness.input.document.DocumentConversionResult;
import com.harness.input.document.DocumentConversionService;
import com.harness.input.document.DocumentSummarizer;
import com.harness.input.multimodal.MarkdownChunk;
import com.harness.input.multimodal.TextChunker;
import com.harness.provider.EmbeddingModelProvider;
import com.harness.tool.knowledge.authority.*;
import com.harness.tool.rag.VectorStore;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** Recoverable immutable-Artifact to whole-document-Revision ingestion pipeline. */
public final class KnowledgeIngestService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestService.class);
    private static final String COMPILER_ID = "cyrene-document-compiler/v2";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String WIKI_TASK = """
            Create a semantic Wiki discovery card for this source document.
            Return only one JSON object with exactly two string fields: title and summary.
            title: a meaningful document title, nonblank and at most 512 characters.
            summary: nonblank, at most 2048 characters; describe subjects, important information,
            coverage and the kinds of questions this document can answer. Include distinguishing terms.
            Match the document's language. Do not copy a long excerpt or invent facts.
            The document remains the source of truth; this card is only for discovering relevant knowledge.
            """;
    private static final int EMBEDDING_BATCH_SIZE = 10;

    private final EmbeddingModelProvider embeddingProvider;
    private final VectorStore vectorStore;
    private final DocumentConversionService documentConversionService;
    private final DocumentSummarizer documentSummarizer;
    private final ContentAddressedArtifactStorage artifactStorage;
    private final KnowledgeArtifactRepository artifactRepository;
    private final KnowledgeIngestJobStore ingestJobStore;
    private final KnowledgeRepository knowledgeRepository;
    private final WikiIdentityResolver identityResolver;
    private final TextChunker textChunker;
    private final String defaultCollection;
    private final long maxFileSizeMb;
    private final int chunkSize;
    private final int maxChunks;
    private final int maxAttempts;

    public KnowledgeIngestService(
            EmbeddingModelProvider embeddingProvider,
            VectorStore vectorStore,
            DocumentConversionService documentConversionService,
            DocumentSummarizer documentSummarizer,
            ContentAddressedArtifactStorage artifactStorage,
            KnowledgeArtifactRepository artifactRepository,
            KnowledgeIngestJobStore ingestJobStore,
            KnowledgeRepository knowledgeRepository
    ) {
        this(embeddingProvider, vectorStore, documentConversionService, documentSummarizer,
                artifactStorage, artifactRepository, ingestJobStore, knowledgeRepository, null);
    }

    public KnowledgeIngestService(
            EmbeddingModelProvider embeddingProvider,
            VectorStore vectorStore,
            DocumentConversionService documentConversionService,
            DocumentSummarizer documentSummarizer,
            ContentAddressedArtifactStorage artifactStorage,
            KnowledgeArtifactRepository artifactRepository,
            KnowledgeIngestJobStore ingestJobStore,
            KnowledgeRepository knowledgeRepository,
            WikiIdentityResolver identityResolver
    ) {
        this.embeddingProvider = Objects.requireNonNull(embeddingProvider, "embeddingProvider");
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore");
        this.documentConversionService = Objects.requireNonNull(documentConversionService, "documentConversionService");
        this.documentSummarizer = Objects.requireNonNull(documentSummarizer, "documentSummarizer");
        this.artifactStorage = Objects.requireNonNull(artifactStorage, "artifactStorage");
        this.artifactRepository = Objects.requireNonNull(artifactRepository, "artifactRepository");
        this.ingestJobStore = Objects.requireNonNull(ingestJobStore, "ingestJobStore");
        this.knowledgeRepository = Objects.requireNonNull(knowledgeRepository, "knowledgeRepository");
        this.identityResolver = identityResolver;
        this.textChunker = new TextChunker(embeddingProvider.tokenEstimator());

        EnvConfig config = EnvConfig.get();
        this.defaultCollection = config.getString(EnvKey.RAG_COLLECTION, "default");
        this.maxFileSizeMb = config.getLong(EnvKey.KNOWLEDGE_MAX_FILE_SIZE_MB, 50);
        this.chunkSize = config.getInt(EnvKey.KNOWLEDGE_CHUNK_SIZE, 1024);
        this.maxChunks = config.getInt(EnvKey.KNOWLEDGE_SOURCE_REVISION_MAX_CHUNKS, 10_000);
        this.maxAttempts = config.getInt(EnvKey.KNOWLEDGE_INGEST_MAX_ATTEMPTS, 5);
        if (maxChunks < 1 || maxAttempts < 1) {
            throw new IllegalArgumentException("Knowledge ingest limits must be positive");
        }
    }

    public IngestResult ingest(byte[] fileData, String fileName, String mimeType, String collection) {
        return ingest(fileData, fileName, mimeType, collection, null, null);
    }

    /** documentId is the stable Source Document Concept ID, never a filename or Chunk ID. */
    public IngestResult ingest(
            byte[] fileData,
            String fileName,
            String mimeType,
            String collection,
            String documentId,
            String tenantId
    ) {
        long started = System.currentTimeMillis();
        validateUpload(fileData, fileName);
        String collectionKey = normalizeCollection(collection);
        String conceptId = resolveConceptId(documentId, tenantId, collectionKey);
        Instant now = Instant.now();
        ContentAddressedArtifactStorage.StoredArtifact stored = artifactStorage.store(
                fileData, tenantId, collectionKey, KnowledgeArtifactType.SOURCE_FILE);
        KnowledgeArtifact artifact = new KnowledgeArtifact(
                KnowledgeIdentity.artifactId(tenantId, collectionKey,
                        KnowledgeArtifactType.SOURCE_FILE, stored.contentHash()),
                tenantId, collectionKey, KnowledgeArtifactType.SOURCE_FILE,
                fileName, normalizeMediaType(mimeType), stored.contentHash(),
                stored.storageUri(), KnowledgeArtifact.Status.ACTIVE, now);
        KnowledgeIngestJob job = new KnowledgeIngestJob(
                UUID.randomUUID().toString().replace("-", ""), artifact.id(), tenantId,
                collectionKey, KnowledgeIngestJob.Status.UPLOADED, 1, now, now,
                null, conceptId, null, null, now, null);
        artifactRepository.registerWithIngestJob(artifact, job);

        try {
            processClaimed(job);
        } catch (RuntimeException failure) {
            recordFailure(job, failure);
            throw ingestFailure(job, artifact, failure);
        }
        KnowledgeIngestJob completed;
        try {
            completed = runToCompletion(job.id());
        } catch (RuntimeException failure) {
            throw ingestFailure(job, artifact, failure);
        }
        String completedConceptId = completed.sourceConceptId();
        KnowledgeHead head = knowledgeRepository.findById(completedConceptId).orElseThrow(
                () -> new IllegalStateException("Compiled Source Document is missing"));
        return new IngestResult(
                completed.id(), completedConceptId, completed.sourceRevisionId(), artifact.id(),
                completed.convertedArtifactId(), fileName, collectionKey,
                numberMetadata(head.currentRevision(), "chunkCount"),
                embeddingProvider.dimension(), artifact.storageUri(),
                System.currentTimeMillis() - started);
    }

    /** Process one durable stage for the next available Job. */
    public boolean processNext() {
        Optional<KnowledgeIngestJob> claimed = ingestJobStore.claimNext(Instant.now());
        if (claimed.isEmpty()) {
            return false;
        }
        KnowledgeIngestJob job = claimed.get();
        try {
            processClaimed(job);
        } catch (RuntimeException failure) {
            recordFailure(job, failure);
            throw failure;
        }
        return true;
    }

    public int recoverStuck() {
        long stuckMinutes = EnvConfig.get().getLong(
                EnvKey.KNOWLEDGE_INGEST_STUCK_MINUTES, 30);
        if (stuckMinutes < 1) {
            throw new IllegalArgumentException(
                    "HARNESS_KNOWLEDGE_INGEST_STUCK_MINUTES must be positive");
        }
        Instant now = Instant.now();
        return ingestJobStore.recoverStuck(now.minusSeconds(stuckMinutes * 60L), now);
    }

    public KnowledgeIngestJob runToCompletion(String jobId) {
        while (true) {
            KnowledgeIngestJob current = ingestJobStore.findById(jobId).orElseThrow(
                    () -> new IllegalArgumentException("Ingest Job not found: " + jobId));
            if (current.status() == KnowledgeIngestJob.Status.INDEXED) {
                return current;
            }
            if (current.status() == KnowledgeIngestJob.Status.FAILED) {
                throw new IllegalStateException("Knowledge ingest failed: " + current.errorMessage());
            }
            if (current.claimedAt() != null) {
                throw new IllegalStateException(
                        "Ingest Job is already owned by another Worker: " + jobId);
            }
            KnowledgeIngestJob claimed = ingestJobStore.claim(jobId, Instant.now()).orElseThrow(
                    () -> new IllegalStateException(
                            "Ingest Job is not currently claimable: " + jobId));
            try {
                processClaimed(claimed);
            } catch (RuntimeException failure) {
                recordFailure(claimed, failure);
                throw failure;
            }
        }
    }

    private void processClaimed(KnowledgeIngestJob job) {
        switch (job.status()) {
            case UPLOADED -> convert(job);
            case CONVERTED -> compile(job);
            case COMPILED -> index(job);
            case INDEXED, FAILED -> throw new IllegalStateException(
                    "Terminal Ingest Job cannot be processed: " + job.id());
        }
    }

    private void convert(KnowledgeIngestJob job) {
        KnowledgeArtifact source = artifactRepository.findById(job.artifactId()).orElseThrow(
                () -> new IllegalStateException("Source Artifact is missing: " + job.artifactId()));
        DocumentConversionResult converted = documentConversionService.convert(
                readArtifact(source), source.fileName(), source.mediaType());
        validateDocumentTypeEnabled(converted.detectedMimeType());
        if (converted.markdown() == null || converted.markdown().isBlank()) {
            throw new IllegalArgumentException("No Markdown content converted from file: " + source.fileName());
        }
        byte[] markdownBytes = converted.markdown().getBytes(StandardCharsets.UTF_8);
        ContentAddressedArtifactStorage.StoredArtifact stored = artifactStorage.store(
                markdownBytes, job.tenantId(), job.collectionKey(),
                KnowledgeArtifactType.CANONICAL_MARKDOWN);
        KnowledgeArtifact markdownArtifact = new KnowledgeArtifact(
                KnowledgeIdentity.artifactId(job.tenantId(), job.collectionKey(),
                        KnowledgeArtifactType.CANONICAL_MARKDOWN, stored.contentHash()),
                job.tenantId(), job.collectionKey(), KnowledgeArtifactType.CANONICAL_MARKDOWN,
                source.fileName() + ".md", "text/markdown", stored.contentHash(),
                stored.storageUri(), KnowledgeArtifact.Status.ACTIVE, Instant.now());
        artifactRepository.register(markdownArtifact);
        ingestJobStore.advance(job.id(), KnowledgeIngestJob.Status.UPLOADED,
                KnowledgeIngestJob.Status.CONVERTED, markdownArtifact.id(),
                null, null, null);
    }

    private void compile(KnowledgeIngestJob job) {
        KnowledgeArtifact source = artifactRepository.findById(job.artifactId()).orElseThrow();
        KnowledgeArtifact markdownArtifact = artifactRepository
                .findById(job.convertedArtifactId()).orElseThrow();
        String markdown = new String(readArtifact(markdownArtifact), StandardCharsets.UTF_8);
        List<MarkdownChunk> chunks = textChunker.chunk(markdown, chunkSize);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("Canonical Markdown has no retrievable content");
        }
        if (chunks.size() > maxChunks) {
            throw new IllegalArgumentException("Source Document exceeds Chunk limit " + maxChunks);
        }

        String expectedJobConceptId = job.sourceConceptId();
        String conceptId = expectedJobConceptId;
        KnowledgeHead existing = knowledgeRepository.findById(conceptId).orElse(null);
        validateExistingDocument(existing, job);
        DocumentSummarizer.Summary summary = documentSummarizer.summarize(markdown, WIKI_TASK, 2048);
        JsonNode card = wikiCard(summary.text());
        if (existing == null && identityResolver != null) {
            var resolution = identityResolver.resolve(
                    KnowledgeConceptType.SOURCE_DOCUMENT, job.tenantId(), null,
                    KnowledgeNamespaceType.COLLECTION, job.collectionKey(), source.fileName(),
                    new WikiIdentityResolver.Draft(
                            card.get("title").asText().strip(),
                            card.get("summary").asText().strip(), markdown),
                    WikiIdentityResolver.RevisionMode.AUTHORITATIVE_SNAPSHOT).orElse(null);
            if (resolution != null) {
                existing = resolution.previous();
                validateExistingDocument(existing, job);
                conceptId = existing.concept().id();
            }
        }
        Instant now = Instant.now();
        long expectedVersion = existing == null ? 0 : existing.concept().version();
        long revisionNumber = expectedVersion + 1;
        String contentHash = KnowledgeIdentity.sha256(markdown);
        if (existing != null && existing.concept().status() == KnowledgeStatus.STABLE
                && contentHash.equals(existing.currentRevision().contentHash())) {
            ingestJobStore.advance(job.id(), KnowledgeIngestJob.Status.CONVERTED,
                    KnowledgeIngestJob.Status.COMPILED, null, conceptId,
                    existing.currentRevision().id(), null);
            return;
        }
        String revisionId = KnowledgeIdentity.revisionId(
                conceptId, revisionNumber, contentHash);
        String previousRevisionId = existing == null ? null : existing.concept().currentRevisionId();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("documentId", conceptId);
        metadata.put("artifactId", source.id());
        metadata.put("canonicalArtifactId", markdownArtifact.id());
        metadata.put("collection", job.collectionKey());
        metadata.put("fileName", source.fileName());
        metadata.put("mediaType", source.mediaType());
        metadata.put("chunkCount", chunks.size());
        metadata.put("tokenEstimator", textChunker.tokenEstimatorStrategy());
        metadata.put("resourceUri", "cyrene://artifacts/" + source.id());
        metadata.put("summaryModel", summary.model());
        metadata.put("summaryCalls", summary.calls());
        metadata.put("summaryInputBlocks", summary.inputBlocks());
        if (previousRevisionId != null) {
            metadata.put("previousRevisionId", previousRevisionId);
        }
        KnowledgeRevision revision = new KnowledgeRevision(
                revisionId, conceptId, revisionNumber,
                card.get("title").asText().strip(), card.get("summary").asText().strip(),
                markdown, COMPILER_ID, now, contentHash, metadata, now);
        KnowledgeConcept concept = new KnowledgeConcept(
                conceptId, job.tenantId(), null,
                KnowledgeNamespaceType.COLLECTION, job.collectionKey(),
                KnowledgeConceptType.SOURCE_DOCUMENT, null, KnowledgeStatus.STABLE,
                revision.id(), revisionNumber, null,
                existing == null ? now : existing.concept().createdAt(), now);
        List<KnowledgeSource> sources = List.of(
                artifactSource(revision.id(), source, now),
                artifactSource(revision.id(), markdownArtifact, now));
        KnowledgeIndexTask catalogTask = new KnowledgeIndexTask(
                null, concept.id(), revision.id(),
                KnowledgeIndexOperation.UPSERT_CURRENT, KnowledgeIndexTaskStatus.PENDING,
                0, now, null, null, null, now);
        ingestJobStore.commitCompilation(job.id(), expectedJobConceptId, new KnowledgeRevisionChange(
                concept, expectedVersion, revision, sources,
                List.of(), List.of(), List.of(catalogTask)));
    }

    private void index(KnowledgeIngestJob job) {
        if (!embeddingProvider.isAvailable()) {
            throw new IllegalStateException("Embedding model not configured. Set "
                    + ModelConfigKey.EMBEDDING_PROVIDER + " in model.conf.");
        }
        KnowledgeHead head = knowledgeRepository.findById(job.sourceConceptId()).orElseThrow();
        if (!job.sourceRevisionId().equals(head.concept().currentRevisionId())) {
            throw new IllegalStateException("Ingest Job no longer references current Revision");
        }
        KnowledgeRevision revision = head.currentRevision();
        List<MarkdownChunk> chunks = textChunker.chunk(revision.body(), chunkSize);
        List<Embedding> embeddings = embed(chunks);
        KnowledgeArtifact source = artifactRepository.findById(job.artifactId()).orElseThrow();
        List<VectorStore.Document> documents = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            MarkdownChunk chunk = chunks.get(index);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("document_id", head.concept().id());
            metadata.put("concept_id", head.concept().id());
            metadata.put("revision_id", revision.id());
            metadata.put("artifact_id", source.id());
            metadata.put("canonical_artifact_id", job.convertedArtifactId());
            metadata.put("chunk_index", index);
            metadata.put("total_chunks", chunks.size());
            // Preserve exact canonical text across chunks for version export/reindex, including whitespace.
            int fragmentStart = (int) ((long) revision.body().length() * index / chunks.size());
            int fragmentEnd = (int) ((long) revision.body().length() * (index + 1) / chunks.size());
            if (fragmentStart > 0 && Character.isLowSurrogate(revision.body().charAt(fragmentStart))
                    && Character.isHighSurrogate(revision.body().charAt(fragmentStart - 1))) fragmentStart++;
            if (fragmentEnd < revision.body().length() && fragmentEnd > 0
                    && Character.isLowSurrogate(revision.body().charAt(fragmentEnd))
                    && Character.isHighSurrogate(revision.body().charAt(fragmentEnd - 1))) fragmentEnd++;
            metadata.put("canonical_fragment", revision.body().substring(fragmentStart, fragmentEnd));
            metadata.put("start_block_index", chunk.startBlockIndex());
            metadata.put("end_block_index", chunk.endBlockIndex());
            metadata.put("heading_path", chunk.headingPath());
            metadata.put("token_count", chunk.tokenCount());
            metadata.put("token_estimator", textChunker.tokenEstimatorStrategy());
            documents.add(new VectorStore.Document(
                    KnowledgeIdentity.documentChunkId(
                            revision.id(), index, KnowledgeIdentity.sha256(chunk.content())),
                    chunk.content(), source.fileName(), 0, metadata,
                    embeddings.get(index).vector(), index));
        }
        vectorStore.upsert(job.collectionKey(), documents);
        ingestJobStore.advance(job.id(), KnowledgeIngestJob.Status.COMPILED,
                KnowledgeIngestJob.Status.INDEXED, null, null, null, Instant.now());
        log.info("Indexed Source Document {} Revision {} with {} Chunks",
                head.concept().id(), revision.id(), documents.size());
    }

    private List<Embedding> embed(List<MarkdownChunk> chunks) {
        List<TextSegment> segments = chunks.stream().map(MarkdownChunk::content)
                .map(TextSegment::from).toList();
        List<Embedding> embeddings = new ArrayList<>(segments.size());
        for (int index = 0; index < segments.size(); index += EMBEDDING_BATCH_SIZE) {
            embeddings.addAll(embeddingProvider.embedAll(
                    segments.subList(index, Math.min(index + EMBEDDING_BATCH_SIZE, segments.size()))));
        }
        if (embeddings.size() != chunks.size()) {
            throw new IllegalStateException("Embedding count does not match Chunk count");
        }
        return embeddings;
    }

    private void recordFailure(KnowledgeIngestJob job, RuntimeException failure) {
        String message = failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getMessage();
        if (!isRetryable(failure) || job.attempts() >= maxAttempts) {
            ingestJobStore.markFailed(job.id(), Instant.now(), message);
        } else {
            long delay = Math.min(3600L, 60L << Math.min(5, Math.max(0, job.attempts() - 1)));
            ingestJobStore.reschedule(job.id(), Instant.now().plusSeconds(delay), message);
        }
    }

    private RuntimeException ingestFailure(
            KnowledgeIngestJob job,
            KnowledgeArtifact artifact,
            RuntimeException failure
    ) {
        if (!isRetryable(failure)) return failure;
        KnowledgeIngestJob current = ingestJobStore.findById(job.id()).orElse(job);
        if (current.status() == KnowledgeIngestJob.Status.FAILED) return failure;
        return new KnowledgeIngestPendingException(
                job.id(), job.sourceConceptId(), artifact.id(),
                job.collectionKey(), failure);
    }

    private static boolean isRetryable(RuntimeException failure) {
        if (failure instanceof DocumentConversionException conversionFailure) {
            int status = conversionFailure.statusCode();
            return status != 400 && status != 413 && status != 415 && status != 422;
        }
        return !(failure instanceof IllegalArgumentException);
    }

    private String resolveConceptId(String requested, String tenantId, String collectionKey) {
        if (requested == null || requested.isBlank()) {
            return UUID.randomUUID().toString().replace("-", "");
        }
        String conceptId = requested.trim();
        KnowledgeHead head = knowledgeRepository.findById(conceptId).orElseThrow(
                () -> new IllegalArgumentException("Source Document does not exist: " + conceptId));
        if (head.concept().conceptType() != KnowledgeConceptType.SOURCE_DOCUMENT
                || !Objects.equals(normalizeTenant(tenantId), normalizeTenant(head.concept().tenantId()))
                || !collectionKey.equals(head.concept().namespaceKey())) {
            throw new IllegalArgumentException("Source Document does not belong to requested scope");
        }
        return conceptId;
    }

    private static void validateExistingDocument(KnowledgeHead existing, KnowledgeIngestJob job) {
        if (existing == null) return;
        KnowledgeConcept concept = existing.concept();
        if (concept.conceptType() != KnowledgeConceptType.SOURCE_DOCUMENT
                || concept.namespaceType() != KnowledgeNamespaceType.COLLECTION
                || !job.collectionKey().equals(concept.namespaceKey())
                || !Objects.equals(normalizeTenant(job.tenantId()), normalizeTenant(concept.tenantId()))) {
            throw new IllegalArgumentException("Ingest Job Source Document scope differs");
        }
    }

    private byte[] readArtifact(KnowledgeArtifact artifact) {
        try {
            return Files.readAllBytes(artifactStorage.resolveStorageUri(artifact.storageUri()));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to read immutable Artifact " + artifact.id(), e);
        }
    }

    private void validateUpload(byte[] data, String fileName) {
        if (data == null || data.length == 0) throw new IllegalArgumentException("Uploaded file is empty");
        if (fileName == null || fileName.isBlank()) throw new IllegalArgumentException("fileName is required");
        if (!embeddingProvider.isAvailable()) {
            throw new IllegalStateException("Embedding model not configured. Set "
                    + ModelConfigKey.EMBEDDING_PROVIDER + " in model.conf.");
        }
        if (data.length > Math.multiplyExact(maxFileSizeMb, 1024L * 1024L)) {
            throw new IllegalArgumentException("File size exceeds limit " + maxFileSizeMb + "MB");
        }
    }

    private String normalizeCollection(String collection) {
        return collection == null || collection.isBlank() ? defaultCollection : collection.trim();
    }

    private static String normalizeMediaType(String value) {
        return value == null || value.isBlank() ? "application/octet-stream" : value.trim();
    }

    private static String normalizeTenant(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static KnowledgeSource artifactSource(
            String revisionId, KnowledgeArtifact artifact, Instant now) {
        return new KnowledgeSource(revisionId, KnowledgeSourceType.KNOWLEDGE_ARTIFACT,
                artifact.id(), "cyrene://artifacts/" + artifact.id(),
                artifact.createdAt(), now);
    }

    private static int numberMetadata(KnowledgeRevision revision, String key) {
        Object value = revision.metadata().get(key);
        if (value instanceof Number number) return number.intValue();
        throw new IllegalStateException("Revision metadata is missing " + key);
    }

    private static JsonNode wikiCard(String text) {
        try {
            JsonNode card = MAPPER.readTree(text);
            if (card == null || !card.isObject()
                    || !validCardText(card.get("title"), 512) || !validCardText(card.get("summary"), 2048)) {
                throw new IllegalStateException("Document model returned an invalid Wiki title or summary");
            }
            return card;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Document model returned invalid Wiki JSON", e);
        }
    }

    private static boolean validCardText(JsonNode value, int limit) {
        return value != null && value.isTextual() && !value.asText().isBlank()
                && value.asText().strip().length() <= limit;
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
        if (!enabled) throw new IllegalArgumentException(
                "Knowledge ingestion is disabled for MIME type: " + mimeType);
    }

    public String getDefaultCollection() {
        return defaultCollection;
    }
}

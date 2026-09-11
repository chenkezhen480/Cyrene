package com.harness.tool.knowledge;

import com.harness.core.knowledge.*;
import com.harness.tool.knowledge.authority.KnowledgeHead;
import com.harness.tool.knowledge.authority.KnowledgeRepository;
import com.harness.tool.knowledge.authority.KnowledgeRevisionChange;
import com.harness.tool.rag.VectorStore;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Whole-document lifecycle operations; Chunk projections are never edited as facts. */
public final class KnowledgeDocumentLifecycleService {

    private static final String LIFECYCLE_ACTOR = "cyrene-document-lifecycle/v1";

    private final KnowledgeRepository repository;
    private final VectorStore vectorStore;

    public KnowledgeDocumentLifecycleService(
            KnowledgeRepository repository,
            VectorStore vectorStore
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore");
    }

    public DeprecationResult deprecate(
            String collection,
            String documentId,
            String tenantId
    ) {
        String normalizedCollection = required(collection, "collection");
        String normalizedDocumentId = required(documentId, "documentId");
        KnowledgeHead head = repository.findAuthorityById(normalizedDocumentId).orElseThrow(
                () -> new IllegalArgumentException(
                        "Source Document does not exist: " + normalizedDocumentId));
        KnowledgeConcept current = head.concept();
        if (current.conceptType() != KnowledgeConceptType.SOURCE_DOCUMENT
                || current.namespaceType() != KnowledgeNamespaceType.COLLECTION
                || !normalizedCollection.equals(current.namespaceKey())
                || !Objects.equals(normalizeTenant(tenantId),
                        normalizeTenant(current.tenantId()))) {
            throw new IllegalArgumentException(
                    "Source Document does not belong to requested scope");
        }
        if (current.status() == KnowledgeStatus.DEPRECATED) {
            String indexedRevisionId = previousRevisionId(head);
            long deletedChunks = vectorStore.deleteDocumentRevision(
                    normalizedCollection, current.id(), indexedRevisionId);
            return new DeprecationResult(
                    current.id(), current.currentRevisionId(), true, deletedChunks);
        }

        Instant now = Instant.now();
        long revisionNumber = current.version() + 1;
        Map<String, Object> metadata = new LinkedHashMap<>(head.currentRevision().metadata());
        metadata.put("previousRevisionId", head.currentRevision().id());
        metadata.put("deprecatedAt", now.toString());
        String body = head.currentRevision().body();
        String contentHash = KnowledgeIdentity.sha256(body + "\ndeprecated:" + now);
        KnowledgeRevision deprecatedRevision = new KnowledgeRevision(
                KnowledgeIdentity.revisionId(current.id(), revisionNumber, contentHash),
                current.id(), revisionNumber, head.currentRevision().title(),
                head.currentRevision().description(), body, LIFECYCLE_ACTOR, now,
                contentHash, metadata, now);
        KnowledgeConcept deprecatedConcept = new KnowledgeConcept(
                current.id(), current.tenantId(), null, current.namespaceType(),
                current.namespaceKey(), current.conceptType(), current.logicalKey(),
                KnowledgeStatus.DEPRECATED, deprecatedRevision.id(), revisionNumber,
                current.staleAfter(), current.createdAt(), now);
        KnowledgeSource source = new KnowledgeSource(
                deprecatedRevision.id(), KnowledgeSourceType.KNOWLEDGE_CONCEPT,
                head.currentRevision().id(),
                "cyrene://knowledge/" + current.id() + "/revisions/"
                        + head.currentRevision().id(),
                head.currentRevision().generatedAt(), now);
        KnowledgeIndexTask deleteCatalog = new KnowledgeIndexTask(
                null, current.id(), deprecatedRevision.id(),
                KnowledgeIndexOperation.DELETE_CONCEPT, KnowledgeIndexTaskStatus.PENDING,
                0, now, null, null, null, now);
        repository.commitChanges(List.of(new KnowledgeRevisionChange(
                deprecatedConcept, current.version(), deprecatedRevision,
                List.of(source), List.of(), List.of(), List.of(deleteCatalog))));
        long deletedChunks = vectorStore.deleteDocumentRevision(
                normalizedCollection, current.id(), head.currentRevision().id());
        return new DeprecationResult(
                current.id(), deprecatedRevision.id(), true, deletedChunks);
    }

    private static String normalizeTenant(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String previousRevisionId(KnowledgeHead head) {
        Object value = head.currentRevision().metadata().get("previousRevisionId");
        if (!(value instanceof String revisionId) || revisionId.isBlank()) {
            throw new IllegalStateException(
                    "Deprecated Source Document is missing previousRevisionId metadata");
        }
        return revisionId.trim();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    public record DeprecationResult(
            String documentId,
            String revisionId,
            boolean deprecated,
            long deletedChunkCount
    ) {
    }
}

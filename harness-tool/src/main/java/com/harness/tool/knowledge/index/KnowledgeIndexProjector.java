package com.harness.tool.knowledge.index;

import com.harness.core.knowledge.KnowledgeIndexTask;
import com.harness.core.knowledge.KnowledgeStatus;
import com.harness.tool.knowledge.authority.KnowledgeHead;
import com.harness.tool.knowledge.authority.KnowledgeRepository;

import java.util.List;

/** Idempotently projects one Outbox task after re-reading current MySQL authority. */
public final class KnowledgeIndexProjector {

    private final KnowledgeRepository repository;
    private final KnowledgeProjectionStore projectionStore;
    private final KnowledgeProjectionMapper mapper;

    public KnowledgeIndexProjector(
            KnowledgeRepository repository,
            KnowledgeProjectionStore projectionStore,
            KnowledgeProjectionMapper mapper
    ) {
        this.repository = java.util.Objects.requireNonNull(repository, "repository");
        this.projectionStore = java.util.Objects.requireNonNull(
                projectionStore, "projectionStore");
        this.mapper = java.util.Objects.requireNonNull(mapper, "mapper");
    }

    public void project(KnowledgeIndexTask task) {
        if (task == null) {
            throw new IllegalArgumentException("task is required");
        }
        repository.withAuthorityLock(task.conceptId(), () -> projectLocked(task));
    }

    private void projectLocked(KnowledgeIndexTask task) {
        switch (task.operation()) {
            case UPSERT_CURRENT -> upsertCurrent(task);
            case DELETE_REVISION -> projectionStore.deleteRevision(task.revisionId());
            case DELETE_CONCEPT -> deleteConcept(task.conceptId());
        }
    }

    private void deleteConcept(String conceptId) {
        var head = repository.findAuthorityById(conceptId).orElse(null);
        if (head != null && head.concept().conceptType() == com.harness.core.knowledge.KnowledgeConceptType.SOURCE_DOCUMENT)
            repository.deleteDocumentContent(conceptId);
        projectionStore.deleteConcept(conceptId);
    }

    private void upsertCurrent(KnowledgeIndexTask task) {
        KnowledgeHead authority = repository.findAuthorityById(task.conceptId()).orElse(null);
        if (authority == null || authority.concept().status() == KnowledgeStatus.DEPRECATED) {
            deleteConcept(task.conceptId());
            return;
        }
        var snapshot = repository.findSnapshot(task.revisionId());
        var c = authority.concept();
        var r = snapshot.revision();
        if (!c.id().equals(r.conceptId()) || c.conceptType() != snapshot.conceptType())
            throw new IllegalStateException("Task version differs from knowledge authority");
        var versionConcept = new com.harness.core.knowledge.KnowledgeConcept(c.id(), c.tenantId(), c.userId(),
                c.namespaceType(), c.namespaceKey(), c.conceptType(), c.logicalKey(), KnowledgeStatus.STABLE,
                r.id(), r.revisionNumber(), c.staleAfter(), c.createdAt(), c.updatedAt());
        var head = new KnowledgeHead(versionConcept, r);
        var jsonMapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        boolean memory = c.conceptType() == com.harness.core.knowledge.KnowledgeConceptType.USER_EPISODE
                || c.conceptType() == com.harness.core.knowledge.KnowledgeConceptType.OPERATION_PLAYBOOK;
        if (memory) projectionStore.upsert(List.of(mapper.map(head).orElseThrow()
                .withRevisionData(snapshot.withBody("").toJson(jsonMapper))));
        var catalogSnapshot = memory || c.conceptType() == com.harness.core.knowledge.KnowledgeConceptType.SOURCE_DOCUMENT
                ? snapshot.withBody("") : snapshot;
        projectionStore.upsertCatalog(List.of(mapper.mapCatalog(head).orElseThrow()
                .withRevisionData(catalogSnapshot.toJson(jsonMapper))));
        projectionStore.activateRevision(c.id(), c.status() == KnowledgeStatus.STABLE ? c.currentRevisionId() : null);
    }
}

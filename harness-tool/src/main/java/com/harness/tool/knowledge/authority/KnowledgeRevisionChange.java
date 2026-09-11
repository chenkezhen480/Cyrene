package com.harness.tool.knowledge.authority;

import com.harness.core.knowledge.KnowledgeConcept;
import com.harness.core.knowledge.KnowledgeIndexTask;
import com.harness.core.knowledge.KnowledgeLink;
import com.harness.core.knowledge.KnowledgeRevision;
import com.harness.core.knowledge.KnowledgeSource;
import com.harness.core.knowledge.KnowledgeVerification;

import java.util.List;

public record KnowledgeRevisionChange(
        KnowledgeConcept concept,
        long expectedConceptVersion,
        KnowledgeRevision revision,
        List<KnowledgeSource> sources,
        List<KnowledgeVerification> verifications,
        List<KnowledgeLink> links,
        List<KnowledgeIndexTask> indexTasks
) {
    public KnowledgeRevisionChange {
        if (concept == null || revision == null) {
            throw new IllegalArgumentException("concept and revision are required");
        }
        if (expectedConceptVersion < 0) {
            throw new IllegalArgumentException("expectedConceptVersion must not be negative");
        }
        if (concept.version() != expectedConceptVersion + 1) {
            throw new IllegalArgumentException(
                    "concept version must be exactly one greater than expectedConceptVersion");
        }
        if (!concept.id().equals(revision.conceptId())) {
            throw new IllegalArgumentException("revision must belong to concept");
        }
        if (!revision.id().equals(concept.currentRevisionId())) {
            throw new IllegalArgumentException("concept head must reference the new revision");
        }
        sources = List.copyOf(sources == null ? List.of() : sources);
        verifications = List.copyOf(verifications == null ? List.of() : verifications);
        links = List.copyOf(links == null ? List.of() : links);
        indexTasks = List.copyOf(indexTasks == null ? List.of() : indexTasks);
        if (sources.stream().anyMatch(source -> !revision.id().equals(source.revisionId()))) {
            throw new IllegalArgumentException("all sources must reference the new revision");
        }
        if (verifications.stream().anyMatch(
                verification -> !revision.id().equals(verification.revisionId()))) {
            throw new IllegalArgumentException("all verifications must reference the new revision");
        }
        if (indexTasks.stream().anyMatch(task -> !concept.id().equals(task.conceptId())
                || !revision.id().equals(task.revisionId()))) {
            throw new IllegalArgumentException("all index tasks must reference the new head");
        }
    }
}

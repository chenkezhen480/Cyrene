package com.harness.core.knowledge;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeModelTest {

    private static final Instant NOW = Instant.parse("2026-09-01T03:00:00Z");

    @Test
    void concept_preservesNullableTenantWithoutGraphDefault() {
        KnowledgeConcept concept = preferenceConcept(null);

        assertThat(concept.tenantId()).isNull();
        assertThat(concept.isStaleAt(NOW)).isFalse();
    }

    @Test
    void concept_enforcesOwnershipAndPreferenceLogicalKey() {
        assertThatThrownBy(() -> new KnowledgeConcept(
                "concept-1",
                null,
                null,
                KnowledgeNamespaceType.USER_MEMORY,
                null,
                KnowledgeConceptType.USER_PREFERENCE,
                null,
                KnowledgeStatus.STABLE,
                "revision-1",
                1,
                null,
                NOW,
                NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");

        assertThatThrownBy(() -> new KnowledgeConcept(
                "concept-1",
                null,
                "user-1",
                KnowledgeNamespaceType.OPERATION_MEMORY,
                null,
                KnowledgeConceptType.OPERATION_PLAYBOOK,
                "task-type",
                KnowledgeStatus.STABLE,
                "revision-1",
                1,
                null,
                NOW,
                NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not have userId");
    }

    @Test
    void revision_copiesMetadataAndRequiresPositiveRevisionNumber() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("qualityScore", 80);
        KnowledgeRevision revision = revision(metadata);
        metadata.put("qualityScore", 20);

        assertThat(revision.metadata()).containsEntry("qualityScore", 80);
        assertThatThrownBy(() -> new KnowledgeRevision(
                "revision-1", "concept-1", 0, "Title", null, "Body",
                "compiler/v1", NOW, "hash", Map.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void source_requiresInternalStableResource() {
        assertThatThrownBy(() -> new KnowledgeSource(
                "revision-1",
                KnowledgeSourceType.SESSION_MESSAGE,
                "message-1",
                "https://example.com/message-1",
                NOW,
                NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cyrene scheme");
    }

    @Test
    void artifact_preservesNullableTenantScope() {
        KnowledgeArtifact artifact = new KnowledgeArtifact(
                "artifact-1",
                null,
                "manuals",
                KnowledgeArtifactType.SOURCE_FILE,
                "guide.pdf",
                "application/pdf",
                "content-hash",
                "cyrene://artifacts/artifact-1",
                KnowledgeArtifact.Status.ACTIVE,
                NOW);
        assertThat(artifact.tenantId()).isNull();
    }

    @Test
    void outboxStatus_usesCompletedRatherThanBatchSucceededState() {
        KnowledgeIndexTask task = new KnowledgeIndexTask(
                1L,
                "concept-1",
                "revision-1",
                KnowledgeIndexOperation.UPSERT_CURRENT,
                KnowledgeIndexTaskStatus.COMPLETED,
                1,
                NOW,
                NOW,
                NOW,
                null,
                NOW);

        assertThat(task.status().storageValue()).isEqualTo("completed");
        assertThat(task.status().terminal()).isTrue();
    }

    private static KnowledgeConcept preferenceConcept(String tenantId) {
        return new KnowledgeConcept(
                "concept-1",
                tenantId,
                "user-1",
                KnowledgeNamespaceType.USER_MEMORY,
                null,
                KnowledgeConceptType.USER_PREFERENCE,
                "response.verbosity",
                KnowledgeStatus.STABLE,
                "revision-1",
                1,
                null,
                NOW,
                NOW);
    }

    static KnowledgeRevision revision(Map<String, Object> metadata) {
        return new KnowledgeRevision(
                "revision-1",
                "concept-1",
                1,
                "Response verbosity",
                "Current preference for response detail.",
                "# Current preference\n\n普通回答简洁。",
                "cyrene-memory-compiler/v1",
                NOW,
                "hash",
                metadata,
                NOW);
    }
}

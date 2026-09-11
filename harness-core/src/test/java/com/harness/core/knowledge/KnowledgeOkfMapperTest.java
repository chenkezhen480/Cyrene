package com.harness.core.knowledge;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeOkfMapperTest {

    private static final Instant GENERATED_AT = Instant.parse("2026-09-01T03:00:00Z");
    private static final Instant VERIFIED_AT = Instant.parse("2026-09-01T03:10:00Z");
    private static final Instant OBSERVED_AT = Instant.parse("2026-09-01T02:20:00Z");
    private static final Instant STALE_AFTER = Instant.parse("2027-03-01T00:00:00Z");

    @Test
    void mapper_fixesOkfV02TrustLifecycleAndCyreneExtensionFields() {
        KnowledgeConcept concept = concept("revision-1");
        KnowledgeRevision revision = revision("revision-1");
        KnowledgeSource source = new KnowledgeSource(
                "revision-1",
                KnowledgeSourceType.SESSION_MESSAGE,
                "message-101",
                "cyrene://sessions/s1/messages/101",
                OBSERVED_AT,
                OBSERVED_AT);
        KnowledgeVerification passed = new KnowledgeVerification(
                1L,
                "revision-1",
                "human:user-123",
                KnowledgeVerificationType.HUMAN_REVIEWED,
                KnowledgeVerificationResult.PASSED,
                null,
                VERIFIED_AT);
        KnowledgeVerification failed = new KnowledgeVerification(
                2L,
                "revision-1",
                "process:nightly-check",
                KnowledgeVerificationType.BUSINESS_CONFIRMED,
                KnowledgeVerificationResult.FAILED,
                "source mismatch",
                VERIFIED_AT);

        OkfKnowledgeDocument document = new KnowledgeOkfMapper().map(
                concept, revision, List.of(source), List.of(passed, failed));
        Map<String, Object> frontMatter = document.frontMatter();

        assertThat(frontMatter)
                .containsEntry("type", "User Preference")
                .containsEntry("title", "Response verbosity")
                .containsEntry("description", "Current preference for response detail.")
                .containsEntry("status", "stable")
                .containsEntry("stale_after", STALE_AFTER)
                .containsEntry("x-cyrene-concept-id", "concept-1")
                .containsEntry("x-cyrene-revision-id", "revision-1")
                .containsEntry("x-cyrene-logical-key", "response.verbosity");
        assertThat(frontMatter.get("generated")).isEqualTo(Map.of(
                "by", "cyrene-memory-compiler/v1",
                "at", GENERATED_AT));
        assertThat(frontMatter.get("verified")).isEqualTo(List.of(
                Map.of("by", "human:user-123", "at", VERIFIED_AT)));
        assertThat(frontMatter.get("sources")).isEqualTo(List.of(Map.of(
                        "id", "message-101",
                        "resource", "cyrene://sessions/s1/messages/101",
                        "last_modified", OBSERVED_AT)));
        assertThat(document.body()).contains("普通回答简洁");
    }

    @Test
    void mapper_rejectsNonCurrentRevision() {
        assertThatThrownBy(() -> new KnowledgeOkfMapper().map(
                concept("revision-2"), revision("revision-1"), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("current revision");
    }

    @Test
    void okfDocument_preservesUnknownExtensionFields() {
        OkfKnowledgeDocument document = new OkfKnowledgeDocument(
                "User Preference",
                "Title",
                null,
                null,
                new OkfKnowledgeDocument.Generated("compiler/v1", GENERATED_AT),
                List.of(),
                KnowledgeStatus.DRAFT,
                null,
                List.of(),
                Map.of("vendor-review-state", "ready"),
                "Body");

        assertThat(document.frontMatter())
                .containsEntry("vendor-review-state", "ready");
    }

    @Test
    void mapper_preservesImportedUnknownFieldsButReassertsAuthorityIdentifiers() {
        KnowledgeRevision importedRevision = new KnowledgeRevision(
                "revision-1", "concept-1", 1, "Response verbosity",
                "Current preference for response detail.", "Body", "importer/v1",
                GENERATED_AT, "content-hash",
                Map.of("okfExtensions", Map.of(
                        "vendor-review-state", "ready",
                        "x-cyrene-concept-id", "forged")),
                GENERATED_AT);

        Map<String, Object> frontMatter = new KnowledgeOkfMapper().map(
                concept("revision-1"), importedRevision, List.of(), List.of()).frontMatter();

        assertThat(frontMatter)
                .containsEntry("vendor-review-state", "ready")
                .containsEntry("x-cyrene-concept-id", "concept-1");
    }

    private static KnowledgeConcept concept(String currentRevisionId) {
        return new KnowledgeConcept(
                "concept-1",
                null,
                "user-123",
                KnowledgeNamespaceType.USER_MEMORY,
                null,
                KnowledgeConceptType.USER_PREFERENCE,
                "response.verbosity",
                KnowledgeStatus.STABLE,
                currentRevisionId,
                1,
                STALE_AFTER,
                GENERATED_AT,
                GENERATED_AT);
    }

    private static KnowledgeRevision revision(String revisionId) {
        return new KnowledgeRevision(
                revisionId,
                "concept-1",
                1,
                "Response verbosity",
                "Current preference for response detail.",
                "# Current preference\n\n普通回答简洁；架构设计允许展开。",
                "cyrene-memory-compiler/v1",
                GENERATED_AT,
                "content-hash",
                Map.of(),
                GENERATED_AT);
    }
}

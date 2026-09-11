package com.harness.tool.knowledge.okf;

import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeIdentity;
import com.harness.core.knowledge.KnowledgeStatus;
import com.harness.core.knowledge.OkfKnowledgeDocument;
import com.harness.tool.knowledge.authority.KnowledgeRepository;
import com.harness.tool.knowledge.authority.KnowledgeHead;
import com.harness.tool.knowledge.authority.KnowledgeRevisionChange;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OkfImportServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-03T04:00:00Z");
    private static final String PATH = "concepts/user-preference/preference.md";

    @Test
    void reviewAndImport_requireApprovalAndCommitDraftTransactionally() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        when(repository.findByIds(List.of())).thenReturn(Map.of());
        OkfImportService service = service(repository, true);
        OkfBundleScope scope = OkfBundleScope.user(null, "user-1");
        Map<String, String> files = bundle(preferenceDocument(false, true));
        String expectedConceptId = KnowledgeIdentity.preferenceConceptId(
                null, "user-1", "response.verbosity");

        OkfImportReview review = service.review(scope, files);

        assertThat(review.committable()).isTrue();
        assertThat(review.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.action())
                    .isEqualTo(OkfImportReview.Action.CREATE_REQUIRES_APPROVAL);
            assertThat(entry.conceptId()).isEqualTo(expectedConceptId);
        });

        OkfImportResult result = service.importApproved(scope, files, List.of(PATH));
        ArgumentCaptor<List<KnowledgeRevisionChange>> changes = ArgumentCaptor.forClass(List.class);
        verify(repository).commitChanges(changes.capture());
        KnowledgeRevisionChange change = changes.getValue().getFirst();
        assertThat(result.conceptIds()).containsExactly(expectedConceptId);
        assertThat(change.concept().status()).isEqualTo(KnowledgeStatus.DRAFT);
        assertThat(change.revision().generatedBy()).isEqualTo("cyrene-okf-import/v1");
        assertThat(change.sources()).singleElement().satisfies(source -> {
            assertThat(source.sourceId()).isEqualTo("message-1");
            assertThat(source.sourceResource()).isEqualTo("cyrene://session/s1/message/1");
        });
        assertThat(change.verifications()).isEmpty();
        assertThat(change.indexTasks()).isEmpty();
        assertThat(change.revision().metadata())
                .containsKey("okfVerified")
                .containsEntry("okfStatus", "stable");
    }

    @Test
    void review_rejectsMissingSourcesSecretsAndCrossBoundaryTypes() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        OkfImportService service = service(repository, false);

        OkfImportReview missingSource = service.review(
                OkfBundleScope.user(null, "user-1"),
                bundle(preferenceDocument(false, false)));
        assertThat(missingSource.entries().getFirst().issues())
                .contains("at least one source is required for import");

        OkfImportReview secret = service.review(
                OkfBundleScope.user(null, "user-1"),
                bundle(preferenceDocument(true, true)));
        assertThat(secret.entries().getFirst().issues())
                .contains("document contains credential-like data")
                .anyMatch(issue -> issue.contains("does not exist or is not readable"));

        OkfKnowledgeDocument operation = new OkfKnowledgeDocument(
                KnowledgeConceptType.OPERATION_PLAYBOOK.displayName(), "Playbook", null, null,
                new OkfKnowledgeDocument.Generated("other/v1", NOW), List.of(),
                KnowledgeStatus.DRAFT, null,
                List.of(new OkfKnowledgeDocument.Source(
                        "result-1", "cyrene://business/results/1", NOW)),
                Map.of("x-cyrene-logical-key", "task:search"), "steps");
        OkfImportReview crossBoundary = service.review(
                OkfBundleScope.user(null, "user-1"), bundle(operation));
        assertThat(crossBoundary.entries().getFirst().issues())
                .anyMatch(issue -> issue.contains("outside the requested bundle boundary"));
    }

    @Test
    void unchangedRequiresAnExactPreviouslyImportedDocumentHash() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        when(repository.findByIds(List.of())).thenReturn(Map.of());
        OkfImportService service = service(repository, true);
        OkfBundleScope scope = OkfBundleScope.user(null, "user-1");
        Map<String, String> files = bundle(preferenceDocument(false, true));

        service.importApproved(scope, files, List.of(PATH));
        ArgumentCaptor<List<KnowledgeRevisionChange>> changes = ArgumentCaptor.forClass(List.class);
        verify(repository).commitChanges(changes.capture());
        KnowledgeRevisionChange imported = changes.getValue().getFirst();
        when(repository.findById(imported.concept().id()))
                .thenReturn(Optional.of(new KnowledgeHead(
                        imported.concept(), imported.revision())));

        assertThat(service.review(scope, files).entries())
                .singleElement()
                .extracting(OkfImportReview.Entry::action)
                .isEqualTo(OkfImportReview.Action.UNCHANGED);
    }

    private static OkfImportService service(
            KnowledgeRepository repository,
            boolean sourceReadable
    ) {
        return new OkfImportService(
                repository,
                (scope, source) -> sourceReadable,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static OkfKnowledgeDocument preferenceDocument(
            boolean includeSecret,
            boolean includeSource
    ) {
        return new OkfKnowledgeDocument(
                "User Preference", "Response verbosity", "Current preference", null,
                new OkfKnowledgeDocument.Generated("other-system/v1", NOW),
                List.of(new OkfKnowledgeDocument.Verified("human:user-1", NOW)),
                KnowledgeStatus.STABLE, null,
                includeSource
                        ? List.of(new OkfKnowledgeDocument.Source(
                        "message-1", "cyrene://session/s1/message/1", NOW))
                        : List.of(),
                Map.of("x-cyrene-logical-key", "response.verbosity"),
                includeSecret ? "Authorization: Bearer private-value" : "Prefer concise answers.");
    }

    private static Map<String, String> bundle(OkfKnowledgeDocument document) {
        return Map.of(
                "index.md", "---\nokf_version: \"0.2\"\n---\n\n# Index\n",
                "log.md", "# Log\n",
                PATH, new OkfMarkdownCodec().write(document));
    }
}

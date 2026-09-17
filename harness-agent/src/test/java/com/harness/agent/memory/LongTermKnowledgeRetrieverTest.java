package com.harness.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.knowledge.KnowledgeToolRuntimeContext;
import com.harness.core.knowledge.KnowledgeConcept;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeNamespaceType;
import com.harness.core.knowledge.KnowledgeRevision;
import com.harness.core.knowledge.KnowledgeStatus;
import com.harness.core.knowledge.LongTermKnowledgeBudgetAllocator;
import com.harness.core.knowledge.PreferenceActivationContext;
import com.harness.core.knowledge.PreferenceActivationTagRegistry;
import com.harness.core.model.PageInfo;
import com.harness.core.model.PageResponse;
import com.harness.core.runtime.RunTrace;
import com.harness.tool.knowledge.authority.KnowledgeHead;
import com.harness.tool.knowledge.authority.KnowledgeRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LongTermKnowledgeRetrieverTest {

    private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");

    @Test
    void filtersOtherPreferenceItemsIndependentlyByActivationTag() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KnowledgeHead preference = preferenceHead();
        when(repository.findPage(eq("tenant-a"), eq("user-a"),
                eq(KnowledgeNamespaceType.USER_MEMORY),
                eq(KnowledgeConceptType.USER_PREFERENCE), any(), any(), anyInt()))
                .thenReturn(new PageResponse<>(
                        List.of(preference.concept()), new PageInfo(100, "", false)));
        when(repository.findByIds(any())).thenAnswer(invocation -> {
            List<String> ids = invocation.getArgument(0);
            return ids.contains(preference.concept().id())
                    ? Map.of(preference.concept().id(), preference)
                    : Map.of();
        });
        com.harness.core.text.TextTokenEstimator estimator = fixedEstimator();
        LongTermKnowledgeRetriever retriever = new LongTermKnowledgeRetriever(
                repository,
                new LongTermKnowledgeBudgetAllocator(estimator),
                estimator,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        var allocation = retriever.retrieve(
                PreferenceActivationContext.forFinalResponse(
                        Set.of(PreferenceActivationTagRegistry.CODE_TASK)),
                new KnowledgeToolRuntimeContext(
                        "tenant-a", "user-a", null, null,
                        Set.of("knowledge_search", "knowledge_read"), null),
                10_000,
                RunTrace.noop());

        assertThat(allocation.selectedBlocks()).singleElement();
        assertThat(allocation.renderedContext())
                .contains("item-code", "Use camelCase")
                .doesNotContain("item-image", "Prefer watercolor");
    }

    @Test
    void dropsPreferenceWhenTheBatchReadHeadIsNoLongerStable() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KnowledgeHead listedPreference = preferenceHead();
        KnowledgeHead deprecatedPreference = withStatus(
                listedPreference, KnowledgeStatus.DEPRECATED);
        when(repository.findPage(eq("tenant-a"), eq("user-a"),
                eq(KnowledgeNamespaceType.USER_MEMORY),
                eq(KnowledgeConceptType.USER_PREFERENCE), any(), any(), anyInt()))
                .thenReturn(new PageResponse<>(
                        List.of(listedPreference.concept()), new PageInfo(100, "", false)));
        when(repository.findByIds(any())).thenAnswer(invocation -> {
            List<String> ids = invocation.getArgument(0);
            return ids.contains(listedPreference.concept().id())
                    ? Map.of(listedPreference.concept().id(), deprecatedPreference)
                    : Map.of();
        });
        com.harness.core.text.TextTokenEstimator estimator = fixedEstimator();
        LongTermKnowledgeRetriever retriever = new LongTermKnowledgeRetriever(
                repository,
                new LongTermKnowledgeBudgetAllocator(estimator),
                estimator,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        var allocation = retriever.retrieve(
                PreferenceActivationContext.forFinalResponse(
                        Set.of(PreferenceActivationTagRegistry.CODE_TASK)),
                new KnowledgeToolRuntimeContext(
                        "tenant-a", "user-a", null, null,
                        Set.of("knowledge_search", "knowledge_read"), null),
                10_000,
                RunTrace.noop());

        assertThat(allocation.selectedBlocks()).isEmpty();
    }

    private static com.harness.core.text.TextTokenEstimator fixedEstimator() {
        return new com.harness.core.text.TextTokenEstimator() {
            @Override
            public int estimate(String text) {
                return 1;
            }

            @Override
            public String strategyName() {
                return "fixed-test";
            }
        };
    }

    private static KnowledgeHead withStatus(KnowledgeHead head, KnowledgeStatus status) {
        KnowledgeConcept source = head.concept();
        KnowledgeConcept changed = new KnowledgeConcept(
                source.id(),
                source.tenantId(),
                source.userId(),
                source.namespaceType(),
                source.namespaceKey(),
                source.conceptType(),
                source.logicalKey(),
                status,
                source.currentRevisionId(),
                source.version(),
                source.staleAfter(),
                source.createdAt(),
                source.updatedAt());
        return new KnowledgeHead(changed, head.currentRevision());
    }

    private static KnowledgeHead preferenceHead() {
        KnowledgeConcept concept = new KnowledgeConcept(
                "preference-other",
                "tenant-a",
                "user-a",
                KnowledgeNamespaceType.USER_MEMORY,
                null,
                KnowledgeConceptType.USER_PREFERENCE,
                "other",
                KnowledgeStatus.STABLE,
                "preference-revision",
                1,
                null,
                NOW.minusSeconds(60),
                NOW.minusSeconds(30));
        Map<String, Object> metadata = Map.of("preference", Map.of(
                "preferenceKey", "other",
                "items", List.of(
                        Map.of(
                                "itemId", "item-code",
                                "statement", "Use camelCase",
                                "activationTags", List.of("CODE_TASK")),
                        Map.of(
                                "itemId", "item-image",
                                "statement", "Prefer watercolor",
                                "activationTags", List.of("IMAGE_TASK")))));
        KnowledgeRevision revision = new KnowledgeRevision(
                "preference-revision",
                concept.id(),
                1,
                "Other preferences",
                null,
                "stored body",
                "test/compiler",
                NOW.minusSeconds(30),
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                metadata,
                NOW.minusSeconds(30));
        return new KnowledgeHead(concept, revision);
    }

}

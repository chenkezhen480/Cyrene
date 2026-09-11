package com.harness.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.knowledge.KnowledgeToolRuntimeContext;
import com.harness.core.knowledge.KnowledgeConcept;
import com.harness.core.knowledge.KnowledgeConceptCursor;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeContextBlock;
import com.harness.core.knowledge.KnowledgeNamespaceType;
import com.harness.core.knowledge.KnowledgeStatus;
import com.harness.core.knowledge.LongTermKnowledgeBudgetAllocator;
import com.harness.core.knowledge.PreferenceActivationContext;
import com.harness.core.knowledge.PreferenceItem;
import com.harness.core.knowledge.PreferenceKeyRegistry;
import com.harness.core.knowledge.PreferenceRevisionData;
import com.harness.core.runtime.RunTrace;
import com.harness.core.text.TextTokenEstimator;
import com.harness.tool.knowledge.authority.KnowledgeHead;
import com.harness.tool.knowledge.authority.KnowledgeRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Injects only activated User Preferences from the authoritative MySQL current view. */
public final class LongTermKnowledgeRetriever {

    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 5;

    private final KnowledgeRepository repository;
    private final LongTermKnowledgeBudgetAllocator budgetAllocator;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TextTokenEstimator tokenEstimator;

    public LongTermKnowledgeRetriever(
            KnowledgeRepository repository,
            LongTermKnowledgeBudgetAllocator budgetAllocator,
            TextTokenEstimator tokenEstimator,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.budgetAllocator = Objects.requireNonNull(budgetAllocator, "budgetAllocator");
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public LongTermKnowledgeBudgetAllocator.Allocation retrieve(
            PreferenceActivationContext activationContext,
            KnowledgeToolRuntimeContext runtimeContext,
            long contextWindowTokens,
            RunTrace trace
    ) {
        long startedAt = System.nanoTime();
        List<KnowledgeContextBlock> candidates = new ArrayList<>();
        if (runtimeContext.userId() != null) {
            candidates.addAll(preferenceBlocks(runtimeContext, activationContext));
        }
        LongTermKnowledgeBudgetAllocator.Allocation allocation =
                budgetAllocator.allocate(contextWindowTokens, candidates);
        recordTrace(trace, allocation, startedAt);
        return allocation;
    }

    private List<KnowledgeContextBlock> preferenceBlocks(
            KnowledgeToolRuntimeContext context,
            PreferenceActivationContext activationContext
    ) {
        List<KnowledgeContextBlock> blocks = new ArrayList<>();
        for (KnowledgeHead head : currentHeads(listConcepts(
                context.tenantId(), context.userId(), KnowledgeNamespaceType.USER_MEMORY,
                KnowledgeConceptType.USER_PREFERENCE))) {
            PreferenceRevisionData preference = preferenceData(head);
            String content = activatedPreferenceContent(preference, activationContext);
            if (content != null) {
                blocks.add(new KnowledgeContextBlock(
                        KnowledgeConceptType.USER_PREFERENCE,
                        head.concept().id(), head.currentRevision().id(),
                        head.currentRevision().title(), content, "mysqlCurrentPreference"));
            }
        }
        return List.copyOf(blocks);
    }

    private List<KnowledgeConcept> listConcepts(
            String tenantId,
            String userId,
            KnowledgeNamespaceType namespaceType,
            KnowledgeConceptType conceptType
    ) {
        List<KnowledgeConcept> concepts = new ArrayList<>();
        KnowledgeConceptCursor cursor = null;
        for (int pageNumber = 0; pageNumber < MAX_PAGES; pageNumber++) {
            var page = repository.findPage(
                    tenantId, userId, namespaceType, conceptType,
                    KnowledgeStatus.STABLE, cursor, PAGE_SIZE);
            concepts.addAll(page.items());
            if (!page.pageInfo().hasMore()) return List.copyOf(concepts);
            if (page.items().isEmpty()) {
                throw new IllegalStateException("Memory pagination reported more without items");
            }
            KnowledgeConcept last = page.items().getLast();
            KnowledgeConceptCursor next = new KnowledgeConceptCursor(last.updatedAt(), last.id());
            if (next.equals(cursor)) {
                throw new IllegalStateException("Memory pagination cursor did not advance");
            }
            cursor = next;
        }
        throw new IllegalStateException("Memory pagination exceeded the bounded page count");
    }

    private List<KnowledgeHead> currentHeads(List<KnowledgeConcept> concepts) {
        LinkedHashMap<String, KnowledgeConcept> unique = new LinkedHashMap<>();
        concepts.forEach(concept -> unique.putIfAbsent(concept.id(), concept));
        List<String> ids = new ArrayList<>(unique.keySet());
        Map<String, KnowledgeHead> heads = new LinkedHashMap<>();
        for (int start = 0; start < ids.size(); start += PAGE_SIZE) {
            heads.putAll(repository.findByIds(
                    ids.subList(start, Math.min(start + PAGE_SIZE, ids.size()))));
        }
        Instant now = clock.instant();
        return ids.stream().map(heads::get)
                .filter(Objects::nonNull)
                .filter(head -> head.currentRevision() != null)
                .filter(head -> head.concept().status() == KnowledgeStatus.STABLE)
                .filter(head -> !head.concept().isStaleAt(now))
                .filter(head -> head.currentRevision().id().equals(
                        head.concept().currentRevisionId()))
                .toList();
    }

    private PreferenceRevisionData preferenceData(KnowledgeHead head) {
        Object raw = head.currentRevision().metadata().get("preference");
        if (raw == null) {
            throw new IllegalStateException("User Preference Revision lacks preference metadata");
        }
        return objectMapper.convertValue(raw, PreferenceRevisionData.class);
    }

    private static String activatedPreferenceContent(
            PreferenceRevisionData preference,
            PreferenceActivationContext activationContext
    ) {
        if (PreferenceKeyRegistry.OTHER_KEY.equals(preference.preferenceKey())) {
            List<PreferenceItem> items = preference.items().stream()
                    .filter(item -> activationContext.matches(item.activationTags())).toList();
            if (items.isEmpty()) return null;
            StringBuilder content = new StringBuilder("preferenceKey: other\nitems:");
            items.forEach(item -> content.append("\n- itemId: ").append(item.itemId())
                    .append("\n  statement: ").append(item.statement()));
            return content.toString();
        }
        if (!activationContext.matches(preference.activationTags())) return null;
        return "preferenceKey: " + preference.preferenceKey()
                + "\nvalue: " + preference.valueText();
    }

    private void recordTrace(
            RunTrace trace,
            LongTermKnowledgeBudgetAllocator.Allocation allocation,
            long startedAt
    ) {
        if (trace == null) return;
        Map<String, String> metadata = new HashMap<>(trace.snapshot().metadata());
        metadata.put("long_term_knowledge_budget_tokens", String.valueOf(allocation.budgetTokens()));
        metadata.put("long_term_knowledge_used_tokens", String.valueOf(allocation.usedTokens()));
        metadata.put("long_term_knowledge_latency_ms",
                String.valueOf((System.nanoTime() - startedAt) / 1_000_000));
        metadata.put("long_term_knowledge_hits", allocation.selectedBlocks().stream().limit(20)
                .map(block -> block.conceptType().name() + ":" + block.conceptId()
                        + ":" + block.revisionId() + ":tokens="
                        + tokenEstimator.estimate(block.render()) + ":reason=" + block.hitReason())
                .collect(java.util.stream.Collectors.joining(",")));
        trace.putMetadata(metadata);
    }
}

package com.harness.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.knowledge.KnowledgeToolRuntimeContext;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeIdentity;
import com.harness.core.knowledge.KnowledgeNamespaceType;
import com.harness.core.knowledge.KnowledgeRevision;
import com.harness.core.knowledge.KnowledgeStatus;
import com.harness.core.knowledge.LongTermKnowledgeBudgetAllocator;
import com.harness.core.knowledge.PreferenceActivationContext;
import com.harness.core.knowledge.PreferenceActivationTagRegistry;
import com.harness.core.knowledge.PreferenceRevisionData;
import com.harness.core.model.PageInfo;
import com.harness.core.model.PageResponse;
import com.harness.core.runtime.RunTrace;
import com.harness.core.text.TextTokenEstimator;
import com.harness.tool.ToolRegistry;
import com.harness.tool.knowledge.WikiIdentityResolver;
import com.harness.tool.knowledge.authority.KnowledgeHead;
import com.harness.tool.knowledge.authority.KnowledgeRepository;
import com.harness.tool.knowledge.authority.KnowledgeRevisionChange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SaveMemoryToolTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final KnowledgeRepository repository = mock(KnowledgeRepository.class);
    private final Runnable signal = mock(Runnable.class);
    private final SaveMemoryTool tool = new SaveMemoryTool(repository, mapper, Clock.systemUTC(), signal);

    @AfterEach void clear() { KnowledgeToolRuntimeContext.clear(); }

    @Test void writesConversationMemoriesWithTrustedOwnershipAndAtomicOutbox() throws Exception {
        activate("user-a");
        for (String type : List.of("USER_EPISODE", "OPERATION_PLAYBOOK")) {
            var result = mapper.readTree(tool.execute(arguments(type)));
            assertThat(result.path("status").asText()).isEqualTo("pending");
        }
        ArgumentCaptor<List<KnowledgeRevisionChange>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository, times(2)).commitChanges(captor.capture());
        var episode = captor.getAllValues().get(0).getFirst();
        var operation = captor.getAllValues().get(1).getFirst();
        assertThat(episode.concept().userId()).isEqualTo("user-a");
        assertThat(operation.concept().userId()).isNull();
        assertThat(operation.concept().tenantId()).isEqualTo("tenant-a");
        assertThat(operation.concept().conceptType()).isEqualTo(KnowledgeConceptType.OPERATION_PLAYBOOK);
        assertThat(operation.revision().description()).isEqualTo("Searchable summary");
        assertThat(operation.revision().body()).isEqualTo("Observed procedure and verification");
        assertThat(operation.sources()).isEmpty();
        assertThat(operation.indexTasks()).singleElement().satisfies(task ->
                assertThat(task.revisionId()).isEqualTo(operation.revision().id()));
        verify(signal, times(2)).run();
    }

    @Test void rejectsUnsupportedTypesAnonymousUserMemoriesAndSecretsBeforeWriting() {
        activate(null);
        assertThatThrownBy(() -> tool.execute(arguments("USER_EPISODE"))).hasMessageContaining("authenticated user");
        assertThatThrownBy(() -> tool.execute(arguments("USER_PREFERENCE"))).hasMessageContaining("authenticated user");
        activate("user-a");
        assertThatThrownBy(() -> tool.execute(arguments("GRAPH_SCHEMA"))).hasMessageContaining("Only user preferences");
        assertThatThrownBy(() -> tool.execute(arguments("OPERATION_PLAYBOOK")
                .put("content", "password=secret-value"))).hasMessageContaining("credentials");
        assertThatThrownBy(() -> tool.execute(arguments("USER_PREFERENCE")
                .put("content", "password=secret-value"))).hasMessageContaining("credentials");
        verify(repository, never()).commitChanges(any());
    }

    @Test void savesRegisteredPreferenceWithoutIndexingAndMakesItReadableByInjection() throws Exception {
        activate("user-a");
        assertThat(tool.spec().parameters().path("properties").path("memoryType").path("enum").toString())
                .contains("USER_PREFERENCE");
        var args = arguments("USER_PREFERENCE").put("memoryKey", "response.language")
                .put("content", "以后使用中文回答");
        assertThat(mapper.readTree(tool.execute(args)).path("status").asText()).isEqualTo("saved");

        ArgumentCaptor<List<KnowledgeRevisionChange>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).commitChanges(captor.capture());
        var change = captor.getValue().getFirst();
        assertThat(change.concept().id()).isEqualTo(
                KnowledgeIdentity.preferenceConceptId("tenant-a", "user-a", "response.language"));
        assertThat(change.concept().userId()).isEqualTo("user-a");
        assertThat(change.concept().namespaceType()).isEqualTo(KnowledgeNamespaceType.USER_MEMORY);
        assertThat(change.indexTasks()).isEmpty();
        var preference = mapper.convertValue(change.revision().metadata().get("preference"), PreferenceRevisionData.class);
        assertThat(preference.valueText()).isEqualTo("以后使用中文回答");
        assertThat(preference.activationTags()).containsExactly(PreferenceActivationTagRegistry.GENERAL_RESPONSE);
        verifyNoInteractions(signal);

        KnowledgeHead head = new KnowledgeHead(change.concept(), change.revision());
        when(repository.findPage(eq("tenant-a"), eq("user-a"), eq(KnowledgeNamespaceType.USER_MEMORY),
                eq(KnowledgeConceptType.USER_PREFERENCE), any(), any(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(change.concept()), new PageInfo(100, "", false)));
        when(repository.findByIds(any())).thenReturn(Map.of(change.concept().id(), head));
        TextTokenEstimator estimator = mock(TextTokenEstimator.class);
        when(estimator.estimate(anyString())).thenReturn(1);
        var retriever = new LongTermKnowledgeRetriever(repository, new LongTermKnowledgeBudgetAllocator(estimator),
                estimator, mapper, Clock.systemUTC());
        var allocation = retriever.retrieve(PreferenceActivationContext.forFinalResponse(Set.of()),
                KnowledgeToolRuntimeContext.requireCurrent(SaveMemoryTool.TOOL_NAME), 10_000, RunTrace.noop());
        assertThat(allocation.renderedContext()).contains("response.language", "以后使用中文回答");
    }

    @Test void updatesPreferenceItemsWithoutLosingOtherItemsAndSkipsIdenticalWrites() throws Exception {
        activate("user-a");
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        doAnswer(call -> {
            List<KnowledgeRevisionChange> changes = call.getArgument(0);
            var change = changes.getFirst();
            when(repository.findById(change.concept().id()))
                    .thenReturn(Optional.of(new KnowledgeHead(change.concept(), change.revision())));
            return null;
        }).when(repository).commitChanges(any());

        var report = arguments("USER_PREFERENCE").put("memoryKey", "report.riskOrder")
                .put("content", "报告先列风险");
        report.putArray("activationTags").add("REPORT_TASK");
        var image = arguments("USER_PREFERENCE").put("memoryKey", "image.palette")
                .put("content", "配图使用冷色调");
        image.putArray("activationTags").add("IMAGE_TASK");
        tool.execute(report);
        tool.execute(image);
        report.put("content", "报告先列风险，再列计划");
        tool.execute(report);
        assertThat(mapper.readTree(tool.execute(report)).path("status").asText()).isEqualTo("unchanged");

        ArgumentCaptor<List<KnowledgeRevisionChange>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository, times(3)).commitChanges(captor.capture());
        var update = captor.getAllValues().getLast().getFirst();
        assertThat(update.expectedConceptVersion()).isEqualTo(2);
        assertThat(update.concept().logicalKey()).isEqualTo("other");
        assertThat(update.concept().id()).isEqualTo(KnowledgeIdentity.preferenceConceptId("tenant-a", "user-a", "other"));
        var preference = mapper.convertValue(update.revision().metadata().get("preference"), PreferenceRevisionData.class);
        assertThat(preference.items()).hasSize(2).anySatisfy(item -> {
            assertThat(item.statement()).isEqualTo("报告先列风险，再列计划");
            assertThat(item.activationTags()).containsExactly("REPORT_TASK");
        }).anySatisfy(item -> assertThat(item.statement()).isEqualTo("配图使用冷色调"));
        assertThat(update.indexTasks()).isEmpty();
        verifyNoInteractions(signal);
    }

    @Test void rejectsInvalidPreferenceActivationTagsAndExpiryBeforeWriting() {
        activate("user-a");
        var args = arguments("USER_PREFERENCE");
        assertThatThrownBy(() -> tool.execute(args)).hasMessageContaining("activationTags must not be empty");
        args.putArray("activationTags").add("MODEL_INVENTED_TASK");
        assertThatThrownBy(() -> tool.execute(args)).hasMessageContaining("unregistered activationTag");
        args.putArray("activationTags").add(1);
        assertThatThrownBy(() -> tool.execute(args)).hasMessageContaining("must contain strings");
        args.putArray("activationTags").add("REPORT_TASK");
        args.put("expiresAt", "2099-01-01T00:00:00Z");
        assertThatThrownBy(() -> tool.execute(args)).hasMessageContaining("Custom preferences do not accept expiresAt");
        args.remove("expiresAt");
        args.put("memoryKey", "response.language");
        assertThatThrownBy(() -> tool.execute(args)).hasMessageContaining("registered defaults");
        verify(repository, never()).commitChanges(any());
        verifyNoInteractions(signal);
    }

    @Test void reportsPersistenceFailureAndDoesNotSignalIndex() {
        activate("user-a");
        doThrow(new IllegalStateException("transaction failed")).when(repository).commitChanges(any());
        assertThatThrownBy(() -> tool.execute(arguments("USER_EPISODE")))
                .hasMessageContaining("transaction failed");
        verifyNoInteractions(signal);
    }

    @Test void appendsARevisionWhenTheModelIdentifiesTheSameMemory() throws Exception {
        WikiIdentityResolver identityResolver = mock(WikiIdentityResolver.class);
        SaveMemoryTool resolvingTool = new SaveMemoryTool(repository, mapper, Clock.systemUTC(), signal,
                com.harness.core.knowledge.PreferenceKeyRegistry.standard(), identityResolver);
        ToolRegistry registry = new ToolRegistry();
        registry.register(resolvingTool);
        KnowledgeToolRuntimeContext.activate("tenant-a", null, null, null, registry.snapshot());

        Instant createdAt = Instant.parse("2026-09-14T00:00:00Z");
        KnowledgeRevision previousRevision = new KnowledgeRevision("revision-v1", "concept-1", 1,
                "原神角色二创图", "生成前查官方设定", "先搜索官方外观设定", SaveMemoryTool.TOOL_NAME,
                createdAt, "hash-v1", Map.of("captureMode", "conversation"), createdAt);
        var previousConcept = new com.harness.core.knowledge.KnowledgeConcept(
                "concept-1", "tenant-a", null, KnowledgeNamespaceType.OPERATION_MEMORY, null,
                KnowledgeConceptType.OPERATION_PLAYBOOK, "genshin_character_fanart_generation",
                KnowledgeStatus.STABLE, previousRevision.id(), 1, null, createdAt, createdAt);
        KnowledgeHead previous = new KnowledgeHead(previousConcept, previousRevision);
        var merged = new WikiIdentityResolver.Draft(
                "IP角色二创图：先查经验与官方设定",
                "生成前先检索已有经验和官方设定",
                "先查操作经验，再搜索官方设定；来源打不开不能证明没有官方设定。");
        when(identityResolver.resolve(eq(KnowledgeConceptType.OPERATION_PLAYBOOK), eq("tenant-a"),
                isNull(), eq(KnowledgeNamespaceType.OPERATION_MEMORY), isNull(), anyString(),
                any(WikiIdentityResolver.Draft.class),
                eq(WikiIdentityResolver.RevisionMode.SYNTHESIZE)))
                .thenReturn(Optional.of(new WikiIdentityResolver.Resolution(previous, merged)));

        var result = mapper.readTree(resolvingTool.execute(arguments("OPERATION_PLAYBOOK")
                .put("memoryKey", "genshin.character.art.officialDesignFirst")));

        assertThat(result.path("conceptId").asText()).isEqualTo("concept-1");
        ArgumentCaptor<List<KnowledgeRevisionChange>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).commitChanges(captor.capture());
        KnowledgeRevisionChange change = captor.getValue().getFirst();
        assertThat(change.expectedConceptVersion()).isEqualTo(1);
        assertThat(change.concept().id()).isEqualTo("concept-1");
        assertThat(change.concept().logicalKey()).isEqualTo("genshin_character_fanart_generation");
        assertThat(change.concept().version()).isEqualTo(2);
        assertThat(change.revision().body()).isEqualTo(merged.content());
    }

    private com.fasterxml.jackson.databind.node.ObjectNode arguments(String type) {
        return mapper.createObjectNode().put("memoryType", type).put("memoryKey", "observed-task")
                .put("title", "Title").put("summary", "Searchable summary")
                .put("content", "Observed procedure and verification");
    }

    private void activate(String userId) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        KnowledgeToolRuntimeContext.activate("tenant-a", userId, null, null, registry.snapshot());
    }
}

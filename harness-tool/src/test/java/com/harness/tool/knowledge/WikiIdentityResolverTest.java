package com.harness.tool.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.knowledge.*;
import com.harness.core.text.UnicodeAwareTextTokenEstimator;
import com.harness.input.document.DocumentSummarizer;
import com.harness.provider.ChatModelProvider;
import com.harness.provider.EmbeddingModelProvider;
import com.harness.tool.knowledge.authority.KnowledgeHead;
import com.harness.tool.knowledge.authority.KnowledgeRepository;
import com.harness.tool.knowledge.index.*;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WikiIdentityResolverTest {
    private final KnowledgeRepository repository = mock(KnowledgeRepository.class);
    private final KnowledgeProjectionStore projectionStore = mock(KnowledgeProjectionStore.class);
    private final EmbeddingModelProvider embeddingProvider = mock(EmbeddingModelProvider.class);
    private final ChatModelProvider chatProvider = mock(ChatModelProvider.class);
    private final ChatModel chatModel = mock(ChatModel.class);
    private WikiIdentityResolver resolver;

    @BeforeEach
    void setUp() {
        when(embeddingProvider.embed(any(String.class))).thenReturn(Embedding.from(new float[]{0.4f, 0.6f}));
        when(embeddingProvider.dimension()).thenReturn(2);
        when(chatProvider.chatModel()).thenReturn(chatModel);
        when(chatProvider.contextWindow()).thenReturn(16_384);
        when(chatProvider.modelName()).thenReturn("primary-model");
        resolver = new WikiIdentityResolver(repository, projectionStore, embeddingProvider,
                new DocumentSummarizer(() -> chatProvider, UnicodeAwareTextTokenEstimator.INSTANCE),
                new ObjectMapper());
    }

    @Test
    void samePlaybookWithDifferentKeysBecomesOneSynthesizedRevision() {
        KnowledgeHead existing = head("concept-1", "genshin_character_fanart_generation",
                "原神角色二创图生成需先搜索官方设定特征", "生成角色二创图时先检索官方外观设定。",
                "先用 web_search 查官方外观，再按发色、服饰和标志性元素生成图片。",
                "tenant-a", null, KnowledgeConceptType.OPERATION_PLAYBOOK,
                KnowledgeNamespaceType.OPERATION_MEMORY, null);
        candidate(existing);
        when(projectionStore.findRevisionSnapshot(existing.currentRevision().id()))
                .thenReturn(java.util.Optional.empty());
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(chat("""
                {"decision":"SAME","candidate":0,
                 "title":"IP角色二创图：先查经验与官方设定",
                 "summary":"生成特定IP角色图片前先检索已有经验和官方设定。",
                 "content":"先查操作经验，再搜索官方视觉设定；查证失败不能当作官方没有设定。"}
                """));

        var result = resolver.resolve(KnowledgeConceptType.OPERATION_PLAYBOOK, "tenant-a", null,
                KnowledgeNamespaceType.OPERATION_MEMORY, null,
                "genshin.character.art.officialDesignFirst",
                new WikiIdentityResolver.Draft("IP角色二创图生成：先查操作经验与官方设定，再出图",
                        "特定IP角色出图前要查询经验和官方设定。",
                        "先 knowledge_search，再 web_search；不要把打不开来源当作没有官方设定。"),
                WikiIdentityResolver.RevisionMode.SYNTHESIZE);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().previous().concept().id()).isEqualTo("concept-1");
        assertThat(result.orElseThrow().draft().content())
                .contains("先查操作经验", "查证失败不能当作官方没有设定");
    }

    @Test
    void authoritativeDocumentReusesIdentityWithoutRewritingItsBody() {
        KnowledgeHead existing = head("doc-1", null, "Guide", "Old guide", "old body",
                "tenant-a", null, KnowledgeConceptType.SOURCE_DOCUMENT,
                KnowledgeNamespaceType.COLLECTION, "docs");
        candidate(existing);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(chat(
                "{\"decision\":\"SAME\",\"candidate\":0,\"title\":null,\"summary\":null,\"content\":null}"));
        var incoming = new WikiIdentityResolver.Draft("Guide v2", "Updated guide", "complete new body");

        var result = resolver.resolve(KnowledgeConceptType.SOURCE_DOCUMENT, "tenant-a", null,
                KnowledgeNamespaceType.COLLECTION, "docs", "guide.pdf", incoming,
                WikiIdentityResolver.RevisionMode.AUTHORITATIVE_SNAPSHOT);

        assertThat(result.orElseThrow().previous().concept().id()).isEqualTo("doc-1");
        assertThat(result.orElseThrow().draft()).isEqualTo(incoming);
        ArgumentCaptor<KnowledgeProjectionSearch> search = ArgumentCaptor.forClass(KnowledgeProjectionSearch.class);
        verify(projectionStore).searchHybrid(search.capture());
        assertThat(search.getValue().exactTenant()).isTrue();
        assertThat(search.getValue().namespaceType()).isEqualTo(KnowledgeNamespaceType.COLLECTION);
        assertThat(search.getValue().namespaceKey()).isEqualTo("docs");
    }

    @Test
    void mysqlScopeRecheckRejectsAnotherNamespaceBeforeCallingTheModel() {
        KnowledgeHead existing = head("doc-1", null, "Guide", "Guide", "body",
                "tenant-a", null, KnowledgeConceptType.SOURCE_DOCUMENT,
                KnowledgeNamespaceType.COLLECTION, "other");
        candidate(existing);

        assertThat(resolver.resolve(KnowledgeConceptType.SOURCE_DOCUMENT, "tenant-a", null,
                KnowledgeNamespaceType.COLLECTION, "docs", "guide.pdf",
                new WikiIdentityResolver.Draft("Guide", "Guide", "body"),
                WikiIdentityResolver.RevisionMode.AUTHORITATIVE_SNAPSHOT)).isEmpty();
        verifyNoInteractions(chatModel);
    }

    private void candidate(KnowledgeHead head) {
        when(projectionStore.searchHybrid(any())).thenReturn(List.of(
                new KnowledgeProjectionHit(projection(head), 0.03)));
        when(repository.findAuthorityByIds(List.of(head.concept().id())))
                .thenReturn(Map.of(head.concept().id(), head));
        when(projectionStore.findRevisionSnapshot(head.currentRevision().id()))
                .thenReturn(java.util.Optional.of(new com.harness.tool.knowledge.authority.KnowledgeRevisionSnapshot(
                        head.concept().conceptType(), head.concept().namespaceKey(),
                        head.currentRevision(), List.of(), List.of())));
    }

    private static ChatResponse chat(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private static KnowledgeHead head(String conceptId, String key, String title,
                                      String summary, String content, String tenantId, String userId,
                                      KnowledgeConceptType type, KnowledgeNamespaceType namespace,
                                      String namespaceKey) {
        Instant now = Instant.parse("2026-09-15T00:00:00Z");
        String revisionId = conceptId + "-revision";
        KnowledgeRevision revision = new KnowledgeRevision(revisionId, conceptId, 1,
                title, summary, content, "compiler", now,
                KnowledgeIdentity.sha256(content), Map.of(), now);
        KnowledgeConcept concept = new KnowledgeConcept(conceptId, tenantId, userId,
                namespace, namespaceKey, type, key, KnowledgeStatus.STABLE, revisionId, 1,
                null, now, now);
        return new KnowledgeHead(concept, revision);
    }

    private static KnowledgeProjection projection(KnowledgeHead head) {
        var concept = head.concept();
        var revision = head.currentRevision();
        return new KnowledgeProjection(revision.id(), concept.id(), revision.id(),
                concept.tenantId(), concept.userId(), concept.namespaceType(), concept.namespaceKey(),
                concept.conceptType(), KnowledgeProjectionMapper.routeTarget(concept.conceptType()),
                "cyrene://knowledge/" + concept.id(), revision.title(), revision.description(),
                revision.body(), revision.generatedAt(), null, concept.logicalKey(), null,
                List.of(), new float[]{0.4f, 0.6f});
    }
}

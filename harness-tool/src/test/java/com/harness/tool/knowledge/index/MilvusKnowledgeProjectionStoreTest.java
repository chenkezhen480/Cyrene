package com.harness.tool.knowledge.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeRevision;
import com.harness.tool.knowledge.authority.KnowledgeRevisionSnapshot;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.response.QueryResp;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MilvusKnowledgeProjectionStoreTest {
    @Test
    void readsTwoWikiCardsWithOneCatalogQuery() {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var first = snapshot("rev-1", "doc-1", "First");
        var second = snapshot("rev-2", "doc-2", "Second");
        var client = mock(MilvusClientV2.class);
        when(client.query(any(QueryReq.class))).thenReturn(QueryResp.builder().queryResults(List.of(
                QueryResp.QueryResult.builder().entity(Map.of("revision_id", "rev-1",
                        "revision_data", first.toJson(mapper))).build(),
                QueryResp.QueryResult.builder().entity(Map.of("revision_id", "rev-2",
                        "revision_data", second.toJson(mapper))).build())).build());
        var store = new MilvusKnowledgeProjectionStore(() -> client,
                new KnowledgeProjectionCollections("documents", "catalog", "episodes", "playbooks"));

        var result = store.findMetadataSnapshots(List.of("rev-1", "rev-2"));

        assertThat(result.keySet()).containsExactlyInAnyOrder("rev-1", "rev-2");
        assertThat(result.get("rev-1").revision().title()).isEqualTo("First");
        assertThat(result.get("rev-2").revision().title()).isEqualTo("Second");
        verify(client, times(1)).query(any(QueryReq.class));
    }

    private static KnowledgeRevisionSnapshot snapshot(String revisionId, String conceptId, String title) {
        var now = Instant.parse("2026-09-23T00:00:00Z");
        var revision = new KnowledgeRevision(revisionId, conceptId, 1, title, "Summary", "", "compiler",
                now, "hash", Map.of(), now);
        return new KnowledgeRevisionSnapshot(KnowledgeConceptType.SOURCE_DOCUMENT, "documents", revision,
                List.of(), List.of());
    }
}

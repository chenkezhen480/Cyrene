package com.harness.tool.rag;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentWindowStoreTest {

    @Test
    void keywordOnlyRetrievalUsesMilvusWithoutEmbeddingAndKeepsRevisionScope() {
        MilvusClientV2 client = mock(MilvusClientV2.class);
        var request = org.mockito.ArgumentCaptor.forClass(io.milvus.v2.service.vector.request.SearchReq.class);
        when(client.search(any())).thenReturn(SearchResp.builder().searchResults(List.of(List.of(
                SearchResp.SearchResult.builder().id("chunk-1").score(0.3f)
                        .entity(Map.of("content", "keyword match", "source", "manual", "chunk_index", 0)).build()))).build());
        var store = new MilvusVectorStore(client, "documents", "manuals", null);
        var options = new com.harness.core.knowledge.KnowledgeSearchOptions(5, 30, 1, 0.5, 0.1, false);
        var result = store.searchDocumentRevisions("manuals", "term", options, Map.of("doc-1", "rev-1"));
        assertThat(result.documents()).hasSize(1);
        verify(client).search(request.capture());
        assertThat(request.getValue().getAnnsField()).isEqualTo("sparse_content");
        assertThat(request.getValue().getTopK()).isEqualTo(30);
        assertThat(request.getValue().getFilter()).contains("manuals", "doc-1", "rev-1");
        var fused = com.harness.tool.knowledge.index.ReciprocalRankFusion.rank(
                List.of("dense"), List.of("keyword"), value -> value, 0.2, 0.8, 60, 2);
        assertThat(fused.getFirst().item()).isEqualTo("keyword");
    }

    @Test
    void managementSearchMatchesFilenameOrContentInMilvusWithBoundedCursor() {
        MilvusClientV2 client = mock(MilvusClientV2.class);
        var iterator = mock(io.milvus.orm.iterator.QueryIterator.class);
        when(iterator.next()).thenReturn(List.of());
        when(client.queryIterator(any())).thenReturn(iterator);
        var store = new MilvusVectorStore(client, "documents", "manuals", null);
        store.listKnowledgeChunks("manuals", "cache", 10, null);
        var request = org.mockito.ArgumentCaptor.forClass(io.milvus.v2.service.vector.request.QueryIteratorReq.class);
        verify(client).queryIterator(request.capture());
        assertThat(request.getValue().getExpr()).contains("source like \"%cache%\"", "content like \"%cache%\"");
        assertThat(request.getValue().getLimit()).isEqualTo(11);
        verify(iterator).close();
    }

    @Test
    void milvusUsesTheSameWindowBoundsAndSortsReturnedChunks() {
        MilvusClientV2 client = mock(MilvusClientV2.class);
        QueryResp response = QueryResp.builder()
                .queryResults(List.of(
                        row("chunk-2", "two", 2),
                        row("chunk-0", "zero", 0),
                        row("chunk-1", "one", 1)))
                .build();
        when(client.query(any(QueryReq.class))).thenReturn(response);
        MilvusVectorStore store = new MilvusVectorStore(
                client, "knowledge_documents", "default", null);

        List<VectorStore.Document> documents =
                store.readDocumentWindow(
                        "tenant-manuals", "doc-1", "revision-1", 1, 1, 1);

        assertThat(documents).extracting(VectorStore.Document::chunkIndex)
                .containsExactly(0, 1, 2);
        ArgumentCaptor<QueryReq> request = ArgumentCaptor.forClass(QueryReq.class);
        verify(client).query(request.capture());
        assertThat(request.getValue().getFilter())
                .contains("collection == \"tenant-manuals\"")
                .contains("metadata[\"document_id\"] == \"doc-1\"")
                .contains("metadata[\"revision_id\"] == \"revision-1\"")
                .contains("chunk_index >= 0")
                .contains("chunk_index <= 2");
        assertThat(request.getValue().getLimit()).isEqualTo(3);
    }

    @Test
    void milvusHybridSearchConstrainsBothRequestsToTheLogicalCollection() {
        MilvusClientV2 client = mock(MilvusClientV2.class);
        when(client.hybridSearch(any(HybridSearchReq.class))).thenReturn(mock(SearchResp.class));
        MilvusVectorStore store = new MilvusVectorStore(
                client, "knowledge_documents", "default", null);

        store.searchHybrid("tenant-manuals", "upload limits", new float[]{0.1f}, 5);

        ArgumentCaptor<HybridSearchReq> request = ArgumentCaptor.forClass(HybridSearchReq.class);
        verify(client).hybridSearch(request.capture());
        assertThat(request.getValue().getSearchRequests())
                .hasSize(2)
                .allSatisfy(search -> assertThat(search.getExpr())
                        .isEqualTo("collection == \"tenant-manuals\""));
    }

    private static QueryResp.QueryResult row(String id, String content, int chunkIndex) {
        return QueryResp.QueryResult.builder()
                .entity(Map.of(
                        "id", id,
                        "content", content,
                        "source", "manual.md",
                        "chunk_index", chunkIndex,
                        "metadata", "{\"document_id\":\"doc-1\","
                                + "\"revision_id\":\"revision-1\","
                                + "\"heading_path\":[\"Manual\"]}"))
                .build();
    }
}

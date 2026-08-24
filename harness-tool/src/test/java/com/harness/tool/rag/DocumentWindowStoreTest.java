package com.harness.tool.rag;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.env.PgConnectionPool;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentWindowStoreTest {

    @Test
    void pgvectorReadsOneDocumentWindowWithStableOrdering() throws Exception {
        EnvConfig.init(Map.of(
                EnvKey.RAG_COLLECTION, "default",
                EnvKey.RAG_PG_TABLE, "knowledge_documents"
        ));
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement(any())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, true, false);
        when(resultSet.getLong("id")).thenReturn(10L, 11L, 12L);
        when(resultSet.getString("content")).thenReturn("zero", "one", "two");
        when(resultSet.getString("source")).thenReturn("manual.md");
        when(resultSet.getString("metadata")).thenReturn(
                "{\"document_id\":\"doc-1\",\"heading_path\":[\"Manual\"]}");
        when(resultSet.getInt("chunk_index")).thenReturn(0, 1, 2);
        when(resultSet.wasNull()).thenReturn(false);

        try (MockedStatic<PgConnectionPool> pool = mockStatic(PgConnectionPool.class)) {
            pool.when(PgConnectionPool::getConnection).thenReturn(connection);
            PgVectorStore store = new PgVectorStore(null);

            List<VectorStore.Document> documents =
                    store.readDocumentWindow("tenant-manuals", "doc-1", 1, 1, 1);

            assertThat(documents).extracting(VectorStore.Document::chunkIndex)
                    .containsExactly(0, 1, 2);
            assertThat(documents).allSatisfy(document ->
                    assertThat(document.metadata()).containsEntry("document_id", "doc-1"));
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(connection).prepareStatement(sql.capture());
            assertThat(sql.getValue())
                    .contains("metadata ->> 'document_id' = ?")
                    .contains("ORDER BY chunk_index ASC, id ASC");
            verify(statement).setString(1, "tenant-manuals");
            verify(statement).setString(2, "doc-1");
            verify(statement).setInt(3, 0);
            verify(statement).setInt(4, 2);
        }
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
                store.readDocumentWindow("tenant-manuals", "doc-1", 1, 1, 1);

        assertThat(documents).extracting(VectorStore.Document::chunkIndex)
                .containsExactly(0, 1, 2);
        ArgumentCaptor<QueryReq> request = ArgumentCaptor.forClass(QueryReq.class);
        verify(client).query(request.capture());
        assertThat(request.getValue().getFilter())
                .contains("collection == \"tenant-manuals\"")
                .contains("metadata[\"document_id\"] == \"doc-1\"")
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
                        "metadata", "{\"document_id\":\"doc-1\",\"heading_path\":[\"Manual\"]}"))
                .build();
    }
}

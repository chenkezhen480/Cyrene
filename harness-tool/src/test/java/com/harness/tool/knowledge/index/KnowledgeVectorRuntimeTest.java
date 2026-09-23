package com.harness.tool.knowledge.index;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.provider.EmbeddingModelProvider;
import dev.langchain4j.data.embedding.Embedding;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.service.collection.request.*;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.response.QueryResp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeVectorRuntimeTest {
    private final MilvusClientV2 client = mock(MilvusClientV2.class);
    private final EmbeddingModelProvider embedding = mock(EmbeddingModelProvider.class);
    private final Map<String, CreateCollectionReq.CollectionSchema> schemas = new HashMap<>();
    private final KnowledgeProjectionCollections collections = new KnowledgeProjectionCollections(
            "documents", "catalog", "episodes", "playbooks");
    private final AtomicInteger connections = new AtomicInteger();
    private KnowledgeVectorRuntime runtime;

    @BeforeEach
    void setup() {
        EnvConfig.init(Map.of(EnvKey.RAG_PROVIDER, "milvus", EnvKey.RAG_COLLECTION, "documents"));
        when(embedding.isAvailable()).thenReturn(true);
        when(embedding.dimension()).thenReturn(4);
        when(embedding.embed(anyString())).thenReturn(Embedding.from(new float[]{1, 0, 0, 0}));
        when(client.createSchema()).thenAnswer(ignored -> CreateCollectionReq.CollectionSchema.builder().build());
        when(client.hasCollection(any())).thenAnswer(call ->
                schemas.containsKey(((HasCollectionReq) call.getArgument(0)).getCollectionName()));
        doAnswer(call -> {
            CreateCollectionReq request = call.getArgument(0);
            schemas.put(request.getCollectionName(), request.getCollectionSchema());
            return null;
        }).when(client).createCollection(any());
        when(client.describeCollection(any())).thenAnswer(call -> {
            var schema = schemas.get(((DescribeCollectionReq) call.getArgument(0)).getCollectionName());
            return DescribeCollectionResp.builder().collectionSchema(schema)
                    .fieldNames(schema.getFieldSchemaList().stream().map(CreateCollectionReq.FieldSchema::getName).toList()).build();
        });
        when(client.query(any())).thenReturn(QueryResp.builder().queryResults(List.of()).build());
        runtime = new KnowledgeVectorRuntime(embedding, collections, () -> {
            connections.incrementAndGet();
            return client;
        });
    }

    @Test
    void expandsExistingTextFieldsWithoutRebuildingData() {
        runtime.prepare(embedding, false).run();
        schemas.get("catalog").getFieldSchemaList().stream()
                .filter(field -> field.getName().equals("description")).findFirst().orElseThrow().setMaxLength(2048);
        runtime.prepare(embedding, false).run();
        var request = org.mockito.ArgumentCaptor.forClass(AlterCollectionFieldReq.class);
        verify(client).alterCollectionField(request.capture());
        assertThat(request.getValue().getCollectionName()).isEqualTo("catalog");
        assertThat(request.getValue().getProperties()).containsEntry("max_length", "8192");
        verify(client, never()).dropCollection(any());
    }

    @Test
    void startsPendingWithoutConnectingAndRejectsVectorAccess() {
        assertThat(runtime.status().state()).isEqualTo("pending");
        assertThatThrownBy(runtime::requireReady).hasMessageContaining("Configure");
        assertThat(connections).hasValue(0);
        verifyNoInteractions(client);
    }

    @Test
    void verifiesRealDimensionBeforeTouchingMilvusAndAllowsCorrection() {
        when(embedding.embed(anyString())).thenReturn(Embedding.from(new float[]{1, 0}));
        assertThatThrownBy(() -> runtime.prepare(embedding, true))
                .hasMessageContaining("configured=4, actual=2");
        assertThat(runtime.status().state()).isEqualTo("failed");
        assertThat(connections).hasValue(0);
        when(embedding.embed(anyString())).thenReturn(Embedding.from(new float[]{1, 0, 0, 0}));
        Runnable activate = runtime.prepare(embedding, true);
        assertThat(runtime.isReady()).isFalse();
        assertThat(schemas.keySet()).containsExactlyInAnyOrder("documents", "catalog", "episodes", "playbooks");
        activate.run();
        assertThat(runtime.isReady()).isTrue();
    }

    @Test
    void existingDimensionMismatchNeverRebuildsCollections() {
        var schema = CreateCollectionReq.CollectionSchema.builder().build();
        schema.addField(AddFieldReq.builder().fieldName("embedding")
                .dataType(DataType.FloatVector).dimension(8).build());
        schemas.put("playbooks", schema);
        assertThatThrownBy(() -> runtime.prepare(embedding, false))
                .hasMessageContaining("playbooks").hasMessageContaining("stored=8");
        verify(client, never()).createCollection(any());
        verify(client, never()).dropCollection(any());
        assertThat(runtime.isReady()).isFalse();
    }

    @Test
    void populatedCollectionBlocksFirstSetupWithAnotherModel() {
        when(client.query(any(QueryReq.class))).thenReturn(QueryResp.builder()
                .queryResults(List.of(QueryResp.QueryResult.builder().entity(Map.of("id", "existing")).build()))
                .build());
        assertThatThrownBy(() -> runtime.prepare(embedding, true)).hasMessageContaining("contains data");
        verify(client, never()).dropCollection(any());
        verify(client, never()).upsert(any());
        assertThat(runtime.isReady()).isFalse();
    }

    @Test
    void retriesAfterCollectionCreationSucceededButIndexCreationFailed() {
        doThrow(new IllegalStateException("index service unavailable")).doNothing()
                .when(client).createIndex(any(CreateIndexReq.class));
        assertThatThrownBy(() -> runtime.prepare(embedding, true)).hasMessageContaining("index service unavailable");
        assertThat(schemas).containsKey("documents");
        runtime.prepare(embedding, true).run();
        assertThat(runtime.isReady()).isTrue();
        verify(client, times(4)).createCollection(any());
        verify(client, never()).dropCollection(any());
    }
}

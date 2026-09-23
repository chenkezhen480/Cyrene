package com.harness.tool.rag;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.index.request.ListIndexesReq;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Milvus collection 初始化器。
 * 负责创建 collection、schema（含 BM25 function）、索引。
 * 与 MilvusVectorStore 分离——初始化逻辑不混入检索层。
 *
 * Collection Schema:
 *   id            VarChar(64)     主键，手动 UUID
 *   content       VarChar(65535)  文本内容
 *   source        VarChar(512)    来源文件名
 *   collection    VarChar(128)    逻辑集合名
 *   embedding     FloatVector     向量嵌入
 *   chunk_index   Int64           分块序号
 *   metadata      JSON            扩展元数据
 *   sparse_content SparseFloatVector  BM25 自动生成，用于全文检索
 */
public class MilvusCollectionInitializer {

    private static final Logger log = LoggerFactory.getLogger(MilvusCollectionInitializer.class);

    /**
     * 确保 collection 存在且 schema 正确。
     * 不存在则创建，已存在则校验维度并补齐缺失索引。
     */
    public static void ensureCollection(int embedDim) {
        ensureCollection(MilvusConnectionPool.getClient(),
                EnvConfig.get().getString(EnvKey.RAG_COLLECTION, "knowledge_documents"), embedDim);
    }

    public static void ensureCollection(MilvusClientV2 client, String collectionName, int embedDim) {
        if (embedDim <= 0) {
            throw new IllegalArgumentException("embedDim must be positive");
        }

        try {
            boolean exists = client.hasCollection(
                    HasCollectionReq.builder().collectionName(collectionName).build());
            if (!exists) {
                createCollection(client, collectionName, embedDim);
            } else {
                validateDimension(client, collectionName, embedDim);
            }
            ensureIndexes(client, collectionName, indexes());
            client.loadCollection(LoadCollectionReq.builder()
                    .collectionName(collectionName).build());
            log.info("[Milvus] Collection '{}' ready", collectionName);
        } catch (Exception e) {
            log.error("[Milvus] Failed to ensure collection '{}': {}", collectionName, e.getMessage(), e);
            throw new IllegalStateException("Milvus collection init failed: " + e.getMessage(), e);
        }
    }

    private static void createCollection(MilvusClientV2 client, String collectionName, int embedDim) {
        CreateCollectionReq.CollectionSchema schema = client.createSchema();

        schema.addField(AddFieldReq.builder()
                .fieldName("id").dataType(DataType.VarChar)
                .maxLength(64).isPrimaryKey(true).autoID(false).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("content").dataType(DataType.VarChar)
                .maxLength(65535).enableAnalyzer(true).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("source").dataType(DataType.VarChar)
                .maxLength(512).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("collection").dataType(DataType.VarChar)
                .maxLength(128).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("embedding").dataType(DataType.FloatVector)
                .dimension(embedDim).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("chunk_index").dataType(DataType.Int64).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("metadata").dataType(DataType.JSON).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("sparse_content").dataType(DataType.SparseFloatVector).build());

        // BM25 function: content → sparse_content
        schema.addFunction(CreateCollectionReq.Function.builder()
                .name("bm25_content")
                .functionType(FunctionType.BM25)
                .inputFieldNames(List.of("content"))
                .outputFieldNames(List.of("sparse_content"))
                .build());

        client.createCollection(CreateCollectionReq.builder()
                .collectionName(collectionName)
                .collectionSchema(schema)
                .build());

    }

    private static List<IndexParam> indexes() {
        // 向量索引 (HNSW + COSINE) + BM25 sparse 索引
        IndexParam vectorIndex = IndexParam.builder()
                .fieldName("embedding")
                .indexType(IndexParam.IndexType.HNSW)
                .metricType(IndexParam.MetricType.COSINE)
                .extraParams(Map.of("M", 16, "efConstruction", 256))
                .build();
        IndexParam sparseIndex = IndexParam.builder()
                .fieldName("sparse_content")
                .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                .metricType(IndexParam.MetricType.BM25)
                .build();
        IndexParam collectionIndex = IndexParam.builder()
                .fieldName("collection")
                .indexName("idx_document_collection")
                .indexType(IndexParam.IndexType.INVERTED)
                .build();
        IndexParam sourceIndex = IndexParam.builder()
                .fieldName("source")
                .indexName("idx_document_source")
                .indexType(IndexParam.IndexType.INVERTED)
                .build();
        IndexParam chunkIndex = IndexParam.builder()
                .fieldName("chunk_index")
                .indexName("idx_document_chunk_index")
                .indexType(IndexParam.IndexType.INVERTED)
                .build();
        return List.of(vectorIndex, sparseIndex, collectionIndex, sourceIndex, chunkIndex);
    }

    public static void validateDimension(MilvusClientV2 client, String collection, int dimension) {
        var schema = client.describeCollection(io.milvus.v2.service.collection.request.DescribeCollectionReq
                .builder().collectionName(collection).build()).getCollectionSchema();
        var embedding = schema.getField("embedding");
        if (embedding == null || !Integer.valueOf(dimension).equals(embedding.getDimension())) {
            throw new IllegalStateException("Milvus collection '" + collection
                    + "' embedding dimension mismatch: configured=" + dimension
                    + ", stored=" + (embedding == null ? "missing" : embedding.getDimension()));
        }
    }

    public static void ensureIndexes(MilvusClientV2 client, String collection, List<IndexParam> indexes) {
        for (IndexParam index : indexes) {
            List<String> existing = client.listIndexes(ListIndexesReq.builder()
                    .collectionName(collection).fieldName(index.getFieldName()).build());
            if (existing.isEmpty()) {
                client.createIndex(CreateIndexReq.builder().collectionName(collection)
                        .indexParams(List.of(index)).build());
            }
        }
    }
}

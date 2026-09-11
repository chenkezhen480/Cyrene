package com.harness.tool.knowledge.index;

import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.index.request.CreateIndexReq;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Creates and validates the Catalog, User Episode, and Operation Playbook projections. */
public final class MilvusKnowledgeProjectionInitializer {

    private static final Logger log = LoggerFactory.getLogger(
            MilvusKnowledgeProjectionInitializer.class);

    private final MilvusClientV2 client;

    public MilvusKnowledgeProjectionInitializer(MilvusClientV2 client) {
        this.client = java.util.Objects.requireNonNull(client, "client");
    }

    public void initialize(
            KnowledgeProjectionCollections collections,
            int embeddingDimension
    ) {
        if (embeddingDimension <= 0) {
            throw new IllegalArgumentException("embeddingDimension must be positive");
        }
        ensureCollection(
                collections.catalogCollection(), embeddingDimension, CollectionKind.CATALOG);
        ensureCollection(
                collections.userKnowledgeCollection(), embeddingDimension,
                CollectionKind.USER_EPISODE);
        ensureCollection(
                collections.operationKnowledgeCollection(), embeddingDimension,
                CollectionKind.OPERATION_PLAYBOOK);
    }

    private void ensureCollection(
            String collection,
            int embeddingDimension,
            CollectionKind kind
    ) {
        try {
            boolean exists = client.hasCollection(HasCollectionReq.builder()
                    .collectionName(collection)
                    .build());
            if (!exists) {
                createCollection(collection, embeddingDimension, kind);
            } else {
                validateCollection(collection, embeddingDimension, kind);
            }
            client.loadCollection(LoadCollectionReq.builder()
                    .collectionName(collection)
                    .build());
            log.info("[Milvus] Knowledge projection '{}' ready (kind={}, dim={})",
                    collection, kind, embeddingDimension);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to initialize Milvus Knowledge projection collection '"
                            + collection + "'", e);
        }
    }

    private void createCollection(
            String collection,
            int embeddingDimension,
            CollectionKind kind
    ) {
        CreateCollectionReq.CollectionSchema schema = client.createSchema();
        addCommonFields(schema, embeddingDimension, kind != CollectionKind.OPERATION_PLAYBOOK);
        addKindFields(schema, kind);
        schema.addFunction(CreateCollectionReq.Function.builder()
                .name("bm25_content")
                .functionType(FunctionType.BM25)
                .inputFieldNames(List.of("content"))
                .outputFieldNames(List.of("sparse_content"))
                .build());
        client.createCollection(CreateCollectionReq.builder()
                .collectionName(collection)
                .description("cyrene-knowledge-" + kind.name().toLowerCase())
                .collectionSchema(schema)
                .build());
        client.createIndex(CreateIndexReq.builder()
                .collectionName(collection)
                .indexParams(indexes(kind))
                .build());
        log.info("[Milvus] Created Knowledge projection '{}' ({})", collection, kind);
    }

    private void validateCollection(
            String collection,
            int embeddingDimension,
            CollectionKind kind
    ) {
        var response = client.describeCollection(DescribeCollectionReq.builder()
                .collectionName(collection)
                .build());
        Set<String> actualFields = new LinkedHashSet<>(response.getFieldNames());
        Set<String> expectedFields = expectedFields(kind);
        if (!actualFields.equals(expectedFields)) {
            throw new IllegalStateException(
                    "Existing Milvus Knowledge projection schema does not match"
                            + ": expected=" + expectedFields + ", actual=" + actualFields);
        }
        var embedding = response.getCollectionSchema().getField("embedding");
        if (embedding == null || !Integer.valueOf(embeddingDimension).equals(
                embedding.getDimension())) {
            throw new IllegalStateException(
                    "Existing Milvus collection embedding dimension does not match: "
                            + collection);
        }
    }

    private static void addCommonFields(
            CreateCollectionReq.CollectionSchema schema,
            int embeddingDimension,
            boolean includeUserId
    ) {
        schema.addField(varchar("id", 64, false, true));
        schema.addField(AddFieldReq.builder().fieldName("revision_data").dataType(DataType.JSON).isNullable(true).build());
        schema.addField(varchar("concept_id", 64, false, false));
        schema.addField(varchar("revision_id", 64, false, false));
        schema.addField(varchar("tenant_id", 128, true, false));
        if (includeUserId) {
            schema.addField(varchar("user_id", 128, true, false));
        }
        schema.addField(varchar("concept_type", 64, false, false));
        schema.addField(varchar("title", 512, false, false));
        schema.addField(varchar("description", 2048, true, false));
        schema.addField(AddFieldReq.builder()
                .fieldName("content")
                .dataType(DataType.VarChar)
                .maxLength(65_535)
                .enableAnalyzer(true)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName("generated_at")
                .dataType(DataType.Int64)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName("embedding")
                .dataType(DataType.FloatVector)
                .dimension(embeddingDimension)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName("sparse_content")
                .dataType(DataType.SparseFloatVector)
                .build());
    }

    private static void addKindFields(
            CreateCollectionReq.CollectionSchema schema,
            CollectionKind kind
    ) {
        switch (kind) {
            case CATALOG -> {
                schema.addField(varchar("namespace_type", 32, false, false));
                schema.addField(varchar("namespace_key", 256, true, false));
                schema.addField(varchar("route_target", 32, false, false));
                schema.addField(varchar("resource_uri", 2048, false, false));
            }
            case USER_EPISODE -> schema.addField(AddFieldReq.builder()
                    .fieldName("event_time")
                    .dataType(DataType.Int64)
                    .build());
            case OPERATION_PLAYBOOK -> {
                schema.addField(varchar("logical_key", 256, false, false));
                schema.addField(AddFieldReq.builder()
                        .fieldName("quality_score")
                        .isNullable(true)
                        .dataType(DataType.Double)
                        .build());
                schema.addField(AddFieldReq.builder()
                        .fieldName("required_tools")
                        .dataType(DataType.JSON)
                        .build());
            }
        }
    }

    private static AddFieldReq varchar(
            String name,
            int maxLength,
            boolean nullable,
            boolean primary
    ) {
        return AddFieldReq.builder()
                .fieldName(name)
                .dataType(DataType.VarChar)
                .maxLength(maxLength)
                .isNullable(nullable)
                .isPrimaryKey(primary)
                .autoID(false)
                .build();
    }

    private static List<IndexParam> indexes(CollectionKind kind) {
        List<IndexParam> indexes = new ArrayList<>();
        indexes.add(IndexParam.builder()
                .fieldName("embedding")
                .indexType(IndexParam.IndexType.HNSW)
                .metricType(IndexParam.MetricType.COSINE)
                .extraParams(Map.of("M", 16, "efConstruction", 256))
                .build());
        indexes.add(IndexParam.builder()
                .fieldName("sparse_content")
                .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                .metricType(IndexParam.MetricType.BM25)
                .build());
        for (String field : scalarIndexFields(kind)) {
            indexes.add(IndexParam.builder()
                    .fieldName(field)
                    .indexType(IndexParam.IndexType.INVERTED)
                    .build());
        }
        return List.copyOf(indexes);
    }

    private static List<String> scalarIndexFields(CollectionKind kind) {
        List<String> common = new ArrayList<>(List.of(
                "concept_id", "revision_id", "tenant_id",
                "concept_type", "generated_at"));
        switch (kind) {
            case CATALOG -> common.addAll(List.of(
                    "user_id", "namespace_type", "namespace_key", "route_target"));
            case USER_EPISODE -> common.addAll(List.of("user_id", "event_time"));
            case OPERATION_PLAYBOOK -> common.addAll(List.of(
                    "logical_key", "quality_score"));
        }
        return List.copyOf(common);
    }

    private static Set<String> expectedFields(CollectionKind kind) {
        LinkedHashSet<String> fields = new LinkedHashSet<>(List.of(
                "revision_data", "id", "concept_id", "revision_id", "tenant_id", "concept_type",
                "title", "description", "content", "generated_at", "embedding",
                "sparse_content"));
        switch (kind) {
            case CATALOG -> fields.addAll(List.of(
                    "user_id", "namespace_type", "namespace_key", "route_target",
                    "resource_uri"));
            case USER_EPISODE -> fields.addAll(List.of("user_id", "event_time"));
            case OPERATION_PLAYBOOK -> fields.addAll(List.of(
                    "logical_key", "quality_score", "required_tools"));
        }
        return Set.copyOf(fields);
    }

    private enum CollectionKind {
        CATALOG,
        USER_EPISODE,
        OPERATION_PLAYBOOK
    }
}

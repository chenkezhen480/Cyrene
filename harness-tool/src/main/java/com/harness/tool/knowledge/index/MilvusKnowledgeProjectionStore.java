package com.harness.tool.knowledge.index;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeNamespaceType;
import com.harness.core.model.PageResponse;
import io.milvus.v2.common.IndexParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.QueryIteratorReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import io.milvus.orm.iterator.QueryIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Milvus implementation for the Catalog and the two dedicated memory indexes. */
public final class MilvusKnowledgeProjectionStore implements KnowledgeProjectionStore {

    private static final Logger log = LoggerFactory.getLogger(
            MilvusKnowledgeProjectionStore.class);

    private final java.util.function.Supplier<MilvusClientV2> client;
    private KnowledgeProjectionCollections collections;

    public MilvusKnowledgeProjectionStore(MilvusClientV2 client) {
        this(() -> java.util.Objects.requireNonNull(client, "client"), null);
    }

    public MilvusKnowledgeProjectionStore(java.util.function.Supplier<MilvusClientV2> client,
                                          KnowledgeProjectionCollections collections) {
        this.client = java.util.Objects.requireNonNull(client, "client");
        this.collections = collections;
    }

    @Override
    public void initialize(
            KnowledgeProjectionCollections collections,
            int embeddingDimension
    ) {
        this.collections = java.util.Objects.requireNonNull(collections, "collections");
        new MilvusKnowledgeProjectionInitializer(client.get())
                .initialize(collections, embeddingDimension);
    }

    @Override
    public void upsert(List<KnowledgeProjection> projections) {
        if (projections == null || projections.isEmpty()) {
            return;
        }
        Map<String, List<KnowledgeProjection>> byCollection = new LinkedHashMap<>();
        for (KnowledgeProjection projection : projections) {
            if (projection.embedding() == null || projection.embedding().length == 0) {
                throw new IllegalArgumentException("Projection embedding is required");
            }
            byCollection.computeIfAbsent(collectionFor(projection.conceptType()), ignored ->
                    new ArrayList<>()).add(projection);
        }
        for (Map.Entry<String, List<KnowledgeProjection>> entry : byCollection.entrySet()) {
            List<JsonObject> rows = entry.getValue().stream()
                    .map(MilvusKnowledgeProjectionStore::row)
                    .toList();
            client.get().upsert(UpsertReq.builder()
                    .collectionName(entry.getKey())
                    .data(rows)
                    .build());
            log.info("[Milvus] Upserted {} Knowledge projections into '{}'",
                    rows.size(), entry.getKey());
        }
    }

    @Override
    public void upsertCatalog(List<KnowledgeProjection> projections) {
        if (projections.isEmpty()) return;
        client.get().upsert(UpsertReq.builder()
                .collectionName(requireCollections().catalogCollection())
                .data(projections.stream().map(value -> row(value, true)).toList()).build());
    }

    @Override
    public java.util.Optional<KnowledgeProjection> findMemory(
            KnowledgeConceptType type, String conceptId, String revisionId) {
        if (type != KnowledgeConceptType.USER_EPISODE
                && type != KnowledgeConceptType.OPERATION_PLAYBOOK) {
            throw new IllegalArgumentException("Only memory blocks can be read here");
        }
        try {
            var response = client.get().query(io.milvus.v2.service.vector.request.QueryReq.builder()
                    .consistencyLevel(io.milvus.v2.common.ConsistencyLevel.STRONG)
                    .collectionName(collectionFor(type))
                    .filter("concept_id == {conceptId} and revision_id == {revisionId}")
                    .filterTemplateValues(Map.of("conceptId", required(conceptId, "conceptId"),
                            "revisionId", required(revisionId, "revisionId")))
                    .outputFields(outputFields(Set.of(type))).limit(1L).build());
            return response.getQueryResults().stream().findFirst()
                    .map(result -> mapProjection(result.getEntity()));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read Milvus memory block", exception);
        }
    }

    @Override
    public java.util.Optional<com.harness.tool.knowledge.authority.KnowledgeRevisionSnapshot> findRevisionSnapshot(String revisionId) {
        var response = client.get().query(io.milvus.v2.service.vector.request.QueryReq.builder()
                    .consistencyLevel(io.milvus.v2.common.ConsistencyLevel.STRONG)
                .collectionName(requireCollections().catalogCollection())
                .filter("revision_id == {revision}").filterTemplateValues(Map.of("revision", revisionId))
                .outputFields(List.of("revision_data")).limit(1L).build());
        if (response.getQueryResults().isEmpty()) return java.util.Optional.empty();
        Object raw = response.getQueryResults().getFirst().getEntity().get("revision_data");
        if (raw == null || raw instanceof JsonNull) throw new IllegalStateException("Wiki version metadata is missing");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var snapshot = com.harness.tool.knowledge.authority.KnowledgeRevisionSnapshot.fromJson(mapper,
                raw instanceof String value ? value : new com.google.gson.Gson().toJson(raw));
        if (snapshot.conceptType() == KnowledgeConceptType.USER_EPISODE
                || snapshot.conceptType() == KnowledgeConceptType.OPERATION_PLAYBOOK) {
            snapshot = snapshot.withBody(findMemory(snapshot.conceptType(), snapshot.revision().conceptId(), revisionId)
                    .orElseThrow(() -> new IllegalStateException("Memory version is missing")).content());
        }
        return java.util.Optional.of(snapshot);
    }

    @Override
    public void activateRevision(String conceptId, String revisionId) {
        QueryIterator iterator = null;
        try {
            var fields = new ArrayList<>(outputFields(Set.of()));
            fields.addAll(List.of("embedding", "revision_data"));
            iterator = client.get().queryIterator(QueryIteratorReq.builder()
                    .consistencyLevel(io.milvus.v2.common.ConsistencyLevel.STRONG)
                    .collectionName(requireCollections().catalogCollection())
                    .expr("concept_id == " + literal(conceptId)
                            + " and (revision_data[\"current\"] == true"
                            + (revisionId == null ? "" : " or revision_id == " + literal(revisionId)) + ")")
                    .outputFields(fields).batchSize(100L).build());
            var gson = new com.google.gson.Gson();
            while (true) {
                var batch = iterator.next();
                if (batch.isEmpty()) break;
                List<JsonObject> updates = new ArrayList<>();
                for (var record : batch) {
                    JsonObject row = gson.toJsonTree(record.getFieldValues()).getAsJsonObject();
                    var value = row.get("revision_data");
                    var data = value.isJsonPrimitive()
                            ? com.google.gson.JsonParser.parseString(value.getAsString()).getAsJsonObject()
                            : value.getAsJsonObject();
                    row.add("revision_data", data);
                    data.addProperty("current", revisionId != null && revisionId.equals(row.get("revision_id").getAsString()));
                    updates.add(row);
                }
                client.get().upsert(UpsertReq.builder().collectionName(requireCollections().catalogCollection()).data(updates).build());
            }
        } catch (Exception e) { throw new IllegalStateException("Cannot activate Wiki version", e); }
        finally { if (iterator != null) iterator.close(); }
    }

    @Override
    public void deleteRevision(String revisionId) {
        String requiredRevisionId = required(revisionId, "revisionId");
        for (String collection : requireCollections().projectionCollections()) {
            client.get().delete(DeleteReq.builder()
                    .collectionName(collection)
                    .filter("revision_id == {revisionId}")
                    .filterTemplateValues(Map.of("revisionId", requiredRevisionId))
                    .build());
        }
    }

    @Override
    public void deleteConcept(String conceptId) {
        String requiredConceptId = required(conceptId, "conceptId");
        for (String collection : requireCollections().projectionCollections()) {
            client.get().delete(DeleteReq.builder()
                    .collectionName(collection)
                    .filter("concept_id == {conceptId}")
                    .filterTemplateValues(Map.of("conceptId", requiredConceptId))
                    .build());
        }
    }

    @Override
    public PageResponse<KnowledgeProjectionIdentity> findIdentityPage(
            String afterRevisionId,
            int limit
    ) {
        validateLimit(limit);
        String after = optional(afterRevisionId);
        Map<String, KnowledgeProjectionIdentity> identities = new LinkedHashMap<>();
        for (String collection : requireCollections().projectionCollections()) {
            for (KnowledgeProjectionIdentity identity : findIdentityPage(
                    collection, after, limit)) {
                identities.putIfAbsent(identity.revisionId(), identity);
            }
        }
        List<KnowledgeProjectionIdentity> rows = identities.values().stream()
                .sorted(Comparator.comparing(KnowledgeProjectionIdentity::revisionId))
                .limit((long) limit + 1L)
                .toList();
        return PageResponse.fromFetched(
                rows, limit, KnowledgeProjectionIdentity::revisionId);
    }

    private List<KnowledgeProjectionIdentity> findIdentityPage(
            String collection,
            String after,
            int limit
    ) {
        QueryIterator iterator = null;
        try {
            iterator = client.get().queryIterator(QueryIteratorReq.builder()
                    .consistencyLevel(io.milvus.v2.common.ConsistencyLevel.STRONG)
                    .collectionName(collection)
                    .expr(after == null
                            ? "id != \"\""
                            : "id > \"" + escape(after) + "\"")
                    .outputFields(List.of("revision_id", "concept_id"))
                    .batchSize((long) limit + 1L)
                    .limit((long) limit + 1L)
                    .build());
            List<KnowledgeProjectionIdentity> rows = new ArrayList<>();
            for (QueryResultsWrapper.RowRecord row : iterator.next()) {
                Map<String, Object> values = row.getFieldValues();
                rows.add(new KnowledgeProjectionIdentity(
                        String.valueOf(values.get("revision_id")),
                        String.valueOf(values.get("concept_id"))));
            }
            return List.copyOf(rows);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to list Milvus Knowledge projection identities", e);
        } finally {
            if (iterator != null) {
                iterator.close();
            }
        }
    }

    @Override
    public KnowledgeProjectionSearchOutcome searchHybrid(KnowledgeProjectionSearch search) {
        java.util.Objects.requireNonNull(search, "search");
        validateSearchScope(search);
        List<KnowledgeProjectionHit> hits = new ArrayList<>();
        List<KnowledgeRetrievalDiagnostics> lanes = new ArrayList<>();
        for (SearchTarget target : searchTargets(search)) {
            LaneSearch lane = searchLanes(target.collection(), target.search());
            hits.addAll(lane.hits());
            lanes.add(lane.diagnostics());
        }
        List<KnowledgeProjectionHit> fused = hits.stream()
                .sorted(Comparator.comparingDouble(KnowledgeProjectionHit::rrfScore)
                        .reversed()
                        .thenComparing(hit -> hit.projection().revisionId()))
                .limit(search.fusedTopK())
                .toList();
        // fusedCandidates is the unique revision count surviving fusion before the final limit;
        // each lane reports its own hit count, so the merge would sum rather than dedupe them.
        KnowledgeRetrievalDiagnostics merged = KnowledgeRetrievalDiagnostics.merge(
                lanes, search.denseThreshold(), search.sparseThreshold());
        return new KnowledgeProjectionSearchOutcome(fused, merged);
    }

    /**
     * Runs the dense and sparse lanes as two plain searches instead of one {@code hybridSearch}.
     *
     * <p>Milvus applies {@code radius} server-side, so a fused response can never say whether a
     * lane was empty or merely below the threshold — the exact question a "why did my search
     * miss" investigation needs answered. Searching the lanes separately keeps the sub-threshold
     * neighbours visible, and the fusion stays identical because
     * {@link ReciprocalRankFusion} uses the same one-based RRF formula as the server ranker.</p>
     */
    private LaneSearch searchLanes(String collection, KnowledgeProjectionSearch search) {
        try {
            String filter = searchFilter(search);
            List<String> outFields = outputFields(search.conceptTypes());
            SearchResp denseResponse = search.bm25Weight() == 1 ? null : client.get().search(SearchReq.builder()
                    .collectionName(collection)
                    .annsField("embedding")
                    .data(List.of(new FloatVec(floatList(search.embedding()))))
                    .topK(search.laneTopK())
                    .filter(filter)
                    .metricType(IndexParam.MetricType.COSINE)
                    .outputFields(outFields)
                    .build());
            SearchResp sparseResponse = search.bm25Weight() == 0 ? null : client.get().search(SearchReq.builder()
                    .collectionName(collection)
                    .annsField("sparse_content")
                    .data(List.of(new EmbeddedText(search.query())))
                    .topK(search.laneTopK())
                    .filter(filter)
                    .metricType(IndexParam.MetricType.BM25)
                    .outputFields(outFields)
                    .build());

            List<SearchResp.SearchResult> denseRows = firstResults(denseResponse);
            List<SearchResp.SearchResult> sparseRows = firstResults(sparseResponse);

            List<KnowledgeProjection> keptDense = new ArrayList<>();
            List<KnowledgeProjection> keptSparse = new ArrayList<>();
            int keptDenseCount = 0;
            int keptSparseCount = 0;
            for (SearchResp.SearchResult row : denseRows) {
                if (scoreOf(row) >= search.denseThreshold()) {
                    keptDense.add(mapProjection(row.getEntity()));
                    keptDenseCount++;
                }
            }
            for (SearchResp.SearchResult row : sparseRows) {
                if (scoreOf(row) >= search.sparseThreshold()) {
                    keptSparse.add(mapProjection(row.getEntity()));
                    keptSparseCount++;
                }
            }
            List<KnowledgeProjectionHit> fused = ReciprocalRankFusion.rank(
                    keptDense, keptSparse, KnowledgeProjection::revisionId,
                    1 - search.bm25Weight(), search.bm25Weight(), search.rrfK(), search.fusedTopK())
                    .stream().map(hit -> new KnowledgeProjectionHit(hit.item(), hit.score())).toList();
            return new LaneSearch(fused, new KnowledgeRetrievalDiagnostics(
                    List.of(collection),
                    denseRows.size(), bestScore(denseRows), search.denseThreshold(), keptDenseCount,
                    sparseRows.size(), bestScore(sparseRows), search.sparseThreshold(),
                    keptSparseCount,
                    fused.size()));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to search Milvus Knowledge projection '" + collection + "'", e);
        }
    }

    private static List<SearchResp.SearchResult> firstResults(SearchResp response) {
        if (response == null || response.getSearchResults() == null
                || response.getSearchResults().isEmpty()) {
            return List.of();
        }
        List<SearchResp.SearchResult> rows = response.getSearchResults().getFirst();
        return rows == null ? List.of() : rows;
    }

    private static double scoreOf(SearchResp.SearchResult result) {
        return result.getScore() == null ? 0.0 : result.getScore();
    }

    private static Double bestScore(List<SearchResp.SearchResult> rows) {
        Double best = null;
        for (SearchResp.SearchResult row : rows) {
            double score = scoreOf(row);
            if (best == null || score > best) {
                best = score;
            }
        }
        return best;
    }

    private record LaneSearch(
            List<KnowledgeProjectionHit> hits,
            KnowledgeRetrievalDiagnostics diagnostics
    ) {
    }

    @Override
    public String providerName() {
        return "milvus";
    }

    static String searchFilter(
            KnowledgeProjectionSearch search
    ) {
        List<String> predicates = new ArrayList<>();
        predicates.add("revision_data[\"current\"] == true");
        if (!search.conceptTypes().isEmpty()) {
            predicates.add("concept_type in [" + search.conceptTypes().stream()
                    .sorted(Comparator.comparing(KnowledgeConceptType::name))
                    .map(type -> literal(type.name()))
                    .collect(java.util.stream.Collectors.joining(", ")) + "]");
        }
        if (search.conceptTypes().contains(KnowledgeConceptType.USER_EPISODE)) {
            String exactTenant = search.tenantId() == null
                    ? "tenant_id is null"
                    : "tenant_id == " + literal(search.tenantId());
            predicates.add("user_id == " + literal(search.userId())
                    + " and " + exactTenant);
        } else {
            String sharedTenant = search.tenantId() == null
                    ? "tenant_id is null"
                    : search.exactTenant()
                    ? "tenant_id == " + literal(search.tenantId())
                    : "(tenant_id == " + literal(search.tenantId())
                    + " or tenant_id is null)";
            predicates.add(sharedTenant);
        }
        if (search.namespaceType() != null) {
            predicates.add("namespace_type == " + literal(search.namespaceType().name()));
            predicates.add(search.namespaceKey() == null
                    ? "namespace_key is null"
                    : "namespace_key == " + literal(search.namespaceKey()));
        }
        return String.join(" and ", predicates);
    }

    private static List<String> outputFields(Set<KnowledgeConceptType> conceptTypes) {
        List<String> fields = new ArrayList<>(List.of(
                "id", "concept_id", "revision_id", "tenant_id", "user_id", "concept_type",
                "title", "description", "content", "generated_at"));
        if (conceptTypes.contains(KnowledgeConceptType.USER_EPISODE)) {
            fields.add("event_time");
        } else if (conceptTypes.contains(KnowledgeConceptType.OPERATION_PLAYBOOK)) {
            fields.remove("user_id");
            fields.addAll(List.of("logical_key", "quality_score", "required_tools"));
        } else {
            fields.addAll(List.of(
                    "namespace_type", "namespace_key", "route_target", "resource_uri"));
        }
        return List.copyOf(fields);
    }

    private static KnowledgeProjection mapProjection(
            Map<String, Object> values
    ) {
        KnowledgeConceptType conceptType = KnowledgeConceptType.valueOf(
                text(values, "concept_type"));
        boolean catalog = values.containsKey("namespace_type");
        boolean episode = !catalog && conceptType == KnowledgeConceptType.USER_EPISODE;
        boolean playbook = !catalog && conceptType == KnowledgeConceptType.OPERATION_PLAYBOOK;
        return new KnowledgeProjection(
                text(values, "id"),
                text(values, "concept_id"),
                text(values, "revision_id"),
                nullableText(values.get("tenant_id")),
                nullableText(values.get("user_id")),
                episode ? KnowledgeNamespaceType.USER_MEMORY
                        : playbook ? KnowledgeNamespaceType.OPERATION_MEMORY
                        : KnowledgeNamespaceType.valueOf(text(values, "namespace_type")),
                episode || playbook ? null : nullableText(values.get("namespace_key")),
                conceptType,
                KnowledgeProjectionMapper.routeTarget(conceptType),
                episode || playbook
                        ? "cyrene://knowledge/" + text(values, "concept_id")
                        + "/revisions/" + text(values, "revision_id")
                        : text(values, "resource_uri"),
                text(values, "title"),
                nullableText(values.get("description")),
                text(values, "content"),
                java.time.Instant.ofEpochMilli(longValue(values.get("generated_at"))),
                episode ? java.time.Instant.ofEpochMilli(longValue(values.get("event_time"))) : null,
                playbook ? text(values, "logical_key") : null,
                playbook ? doubleValue(values.get("quality_score")) : null,
                playbook ? stringList(values.get("required_tools")) : List.of(),
                null);
    }

    private static String text(Map<String, Object> values, String field) {
        String value = nullableText(values.get(field));
        if (value == null) {
            throw new IllegalStateException("Milvus Knowledge hit is missing " + field);
        }
        return value;
    }

    private static String nullableText(Object value) {
        if (value == null || value instanceof JsonNull) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private static Long longValue(Object value) {
        if (value == null || value instanceof JsonNull) return null;
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private static Double doubleValue(Object value) {
        if (value == null || value instanceof JsonNull) return null;
        if (value instanceof Number number) return number.doubleValue();
        return Double.parseDouble(String.valueOf(value));
    }

    private static List<String> stringList(Object value) {
        if (value instanceof JsonArray array) {
            List<String> values = new ArrayList<>();
            array.forEach(item -> values.add(item.getAsString()));
            return List.copyOf(values);
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> values = new ArrayList<>();
            iterable.forEach(item -> values.add(String.valueOf(item)));
            return List.copyOf(values);
        }
        return List.of();
    }

    private static List<Float> floatList(float[] values) {
        List<Float> floats = new ArrayList<>(values.length);
        for (float value : values) floats.add(value);
        return floats;
    }

    private static String literal(String value) {
        return "\"" + escape(value) + "\"";
    }

    private static void validateSearchScope(KnowledgeProjectionSearch search) {
        boolean mismatch = search.conceptTypes().stream()
                .anyMatch(type -> !KnowledgeProjectionMapper.isSearchableType(type));
        if (mismatch) {
            throw new IllegalArgumentException(
                    "Requested Concept type is not searchable through the Wiki index");
        }
    }

    private static JsonObject row(KnowledgeProjection projection) {
        return row(projection, false);
    }

    private static JsonObject row(KnowledgeProjection projection, boolean catalog) {
        JsonObject row = new JsonObject();
        row.add("revision_data", projection.revisionData() == null ? JsonNull.INSTANCE : com.google.gson.JsonParser.parseString(projection.revisionData()));
        row.addProperty("id", projection.id());
        row.addProperty("concept_id", projection.conceptId());
        row.addProperty("revision_id", projection.revisionId());
        addNullable(row, "tenant_id", projection.tenantId());
        if (catalog || projection.conceptType() != KnowledgeConceptType.OPERATION_PLAYBOOK) {
            addNullable(row, "user_id", projection.userId());
        }
        row.addProperty("concept_type", projection.conceptType().name());
        row.addProperty("title", projection.title());
        addNullable(row, "description", projection.description());
        row.addProperty("content", projection.content());
        row.addProperty("generated_at", projection.generatedAt().toEpochMilli());
        row.add("embedding", floats(projection.embedding()));
        if (catalog) {
            row.addProperty("namespace_type", projection.namespaceType().name());
            addNullable(row, "namespace_key", projection.namespaceKey());
            row.addProperty("route_target", projection.routeTarget().name());
            row.addProperty("resource_uri", projection.resourceUri());
            return row;
        }
        switch (projection.conceptType()) {
            case SOURCE_DOCUMENT, GRAPH_SCHEMA, GRAPH_SPACE -> {
                row.addProperty("namespace_type", projection.namespaceType().name());
                addNullable(row, "namespace_key", projection.namespaceKey());
                row.addProperty("route_target", projection.routeTarget().name());
                row.addProperty("resource_uri", projection.resourceUri());
            }
            case USER_EPISODE -> row.addProperty(
                    "event_time", projection.eventTime().toEpochMilli());
            case OPERATION_PLAYBOOK -> {
                row.addProperty("logical_key", projection.logicalKey());
                row.addProperty("quality_score", projection.qualityScore());
                JsonArray requiredTools = new JsonArray();
                projection.requiredTools().forEach(requiredTools::add);
                row.add("required_tools", requiredTools);
            }
            case USER_PREFERENCE -> throw new IllegalArgumentException(
                    "User Preference is not a vector projection");
        }
        return row;
    }

    private static void addNullable(JsonObject row, String field, String value) {
        if (value == null) {
            row.add(field, JsonNull.INSTANCE);
        } else {
            row.addProperty(field, value);
        }
    }

    private static JsonArray floats(float[] values) {
        JsonArray array = new JsonArray(values.length);
        for (float value : values) {
            array.add(value);
        }
        return array;
    }

    private KnowledgeProjectionCollections requireCollections() {
        if (collections == null) {
            throw new IllegalStateException("Knowledge projection store is not initialized");
        }
        return collections;
    }

    private String collectionFor(KnowledgeConceptType conceptType) {
        return requireCollections().projectionCollection(conceptType);
    }

    private List<SearchTarget> searchTargets(KnowledgeProjectionSearch search) {
        Set<KnowledgeConceptType> types = search.conceptTypes().isEmpty()
                ? EnumSet.of(
                KnowledgeConceptType.SOURCE_DOCUMENT,
                KnowledgeConceptType.GRAPH_SCHEMA,
                KnowledgeConceptType.GRAPH_SPACE,
                KnowledgeConceptType.USER_EPISODE,
                KnowledgeConceptType.OPERATION_PLAYBOOK)
                : search.conceptTypes();
        Map<String, EnumSet<KnowledgeConceptType>> grouped = new LinkedHashMap<>();
        for (KnowledgeConceptType type : types) {
            if (type == KnowledgeConceptType.USER_EPISODE && search.userId() == null) continue;
            grouped.computeIfAbsent(collectionFor(type), ignored ->
                    EnumSet.noneOf(KnowledgeConceptType.class)).add(type);
        }
        return grouped.entrySet().stream()
                .map(entry -> new SearchTarget(entry.getKey(), new KnowledgeProjectionSearch(
                        search.query(), search.embedding(), search.tenantId(), search.userId(),
                        search.namespaceType(), search.namespaceKey(), search.exactTenant(),
                        entry.getValue(), search.laneTopK(), search.fusedTopK(),
                        search.denseThreshold(), search.sparseThreshold(), search.rrfK(), search.bm25Weight())))
                .toList();
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record SearchTarget(
            String collection,
            KnowledgeProjectionSearch search
    ) {
    }
}

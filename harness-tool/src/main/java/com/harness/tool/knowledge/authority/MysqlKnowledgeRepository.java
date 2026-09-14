package com.harness.tool.knowledge.authority;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.env.MysqlConnectionPool;
import com.harness.core.knowledge.*;
import com.harness.tool.knowledge.index.KnowledgeProjectionStore;
import com.harness.tool.rag.VectorStore;
import com.harness.core.knowledge.KnowledgeConceptCursor;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeIdentity;
import com.harness.core.knowledge.KnowledgeIndexTask;
import com.harness.core.knowledge.KnowledgeLink;
import com.harness.core.knowledge.KnowledgeLinkCursor;
import com.harness.core.knowledge.KnowledgeLinkType;
import com.harness.core.knowledge.KnowledgeNamespaceType;
import com.harness.core.knowledge.KnowledgeRevision;
import com.harness.core.knowledge.KnowledgeSource;
import com.harness.core.knowledge.KnowledgeSourceCursor;
import com.harness.core.knowledge.KnowledgeSourceType;
import com.harness.core.knowledge.KnowledgeStatus;
import com.harness.core.knowledge.KnowledgeVerification;
import com.harness.core.knowledge.KnowledgeVerificationCursor;
import com.harness.core.model.PageResponse;
import com.harness.core.persistence.SqlConnectionProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class MysqlKnowledgeRepository implements KnowledgeRepository {

    private final SqlConnectionProvider connectionProvider;
    private final ObjectMapper objectMapper;
    private final KnowledgeProjectionStore projectionStore;
    private final VectorStore vectorStore;
    private final ThreadLocal<Connection> projectionConnection = new ThreadLocal<>();
    private static final String HEAD_COLUMNS = "id, tenant_id, user_id, namespace_type, namespace_key, concept_type, logical_key, status, current_revision_id, links, version, stale_after, created_at, updated_at, revision_metadata";
    private static final String HEADS = "(SELECT " + HEAD_COLUMNS.replace("current_revision_id", "current_version AS current_revision_id")
            + ", route_type, route_data FROM knowledge_metadata UNION ALL SELECT " + HEAD_COLUMNS
            + ", NULL AS route_type, JSON_OBJECT() AS route_data FROM user_preferences)";

    public MysqlKnowledgeRepository(ObjectMapper objectMapper) {
        this(MysqlConnectionPool::getConnection, objectMapper);
    }

    public MysqlKnowledgeRepository(
            SqlConnectionProvider connectionProvider,
            ObjectMapper objectMapper
    ) {
        this(connectionProvider, objectMapper, null, null);
    }

    public MysqlKnowledgeRepository(SqlConnectionProvider connectionProvider, ObjectMapper objectMapper,
                                    KnowledgeProjectionStore projectionStore, VectorStore vectorStore) {
        this.connectionProvider = java.util.Objects.requireNonNull(connectionProvider, "connectionProvider");
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper").copy().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.projectionStore = projectionStore;
        this.vectorStore = vectorStore;
    }

    @Override
    public Optional<KnowledgeHead> findById(String conceptId) {
        return Optional.ofNullable(findByIds(List.of(conceptId)).get(conceptId));
    }

    @Override
    public Optional<KnowledgeHead> findAuthorityById(String conceptId) {
        return Optional.ofNullable(findAuthorityByIds(List.of(conceptId)).get(conceptId));
    }

    @Override
    public Map<String, KnowledgeHead> findAuthorityByIds(List<String> ids) {
        if (ids == null || ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id.isBlank()))
            throw new IllegalArgumentException("At most 100 nonblank knowledge IDs are required");
        if (ids.isEmpty()) return Map.of();
        String sql = "SELECT * FROM " + HEADS + " h WHERE id IN ("
                + String.join(",", java.util.Collections.nCopies(ids.size(), "?")) + ")";
        Map<String, KnowledgeHead> heads = new LinkedHashMap<>();
        try (var scope = readConnection(); var statement = scope.connection().prepareStatement(sql)) {
            for (int i = 0; i < ids.size(); i++) statement.setString(i + 1, ids.get(i));
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var concept = mapConcept(rows, "current_revision_id");
                    String header = rows.getString("revision_metadata");
                    KnowledgeRevision revision = header == null ? null : decode(header, KnowledgeRevision.class);
                    if (heads.put(concept.id(), new KnowledgeHead(concept, revision,
                            concept.conceptType() == KnowledgeConceptType.USER_PREFERENCE ? null
                                    : KnowledgeRouteTarget.valueOf(rows.getString("route_type")),
                            decodeRoute(rows.getString("route_data")))) != null)
                        throw new KnowledgePersistenceException("Knowledge ID exists in both authority tables");
                }
            }
        } catch (SQLException e) { throw new KnowledgePersistenceException("Cannot read knowledge authority", e); }
        return Map.copyOf(heads);
    }

    @Override
    public Map<String, KnowledgeHead> findByIds(List<String> ids) {
        var heads = new LinkedHashMap<>(findAuthorityByIds(ids));
        heads.replaceAll((id, head) -> head.currentRevision() == null
                || head.concept().status() == KnowledgeStatus.DEPRECATED ? head
                : head.withRevision(findSnapshot(head.currentVersion()).revision()));
        return Map.copyOf(heads);
    }

    @Override
    public PageResponse<KnowledgeConcept> findPage(
            String tenantId,
            String userId,
            KnowledgeNamespaceType namespaceType,
            KnowledgeConceptType conceptType,
            KnowledgeStatus status,
            KnowledgeConceptCursor cursor,
            int limit
    ) {
        if (namespaceType == null || conceptType == null) {
            throw new IllegalArgumentException("namespaceType and conceptType are required");
        }
        validateLimit(limit);
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM %s
                WHERE tenant_id <=> ? AND user_id <=> ?
                  AND namespace_type = ? AND concept_type = ?
                """.formatted(tableFor(conceptType)));
        if (status != null) {
            sql.append(" AND status = ?");
        }
        if (cursor != null) {
            sql.append(" AND (updated_at < ? OR (updated_at = ? AND id < ?))");
        }
        sql.append(" ORDER BY updated_at DESC, id DESC LIMIT ?");

        List<KnowledgeConcept> concepts = new ArrayList<>();
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int parameter = 1;
            statement.setString(parameter++, normalizeTenant(tenantId));
            statement.setString(parameter++, normalizeOptional(userId));
            statement.setString(parameter++, namespaceType.name());
            statement.setString(parameter++, conceptType.name());
            if (status != null) {
                statement.setString(parameter++, status.storageValue());
            }
            if (cursor != null) {
                Timestamp timestamp = Timestamp.from(cursor.updatedAt());
                statement.setTimestamp(parameter++, timestamp);
                statement.setTimestamp(parameter++, timestamp);
                statement.setString(parameter++, cursor.conceptId());
            }
            statement.setInt(parameter, limit + 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    concepts.add(mapConcept(resultSet));
                }
            }
        } catch (SQLException e) {
            throw new KnowledgePersistenceException("Failed to list knowledge concepts", e);
        }
        return PageResponse.fromFetched(
                concepts,
                limit,
                concept -> concept.updatedAt() + "|" + concept.id());
    }

    @Override
    public PageResponse<KnowledgeConcept> findManagementPage(
            KnowledgeConceptType conceptType, KnowledgeStatus status, KnowledgeConceptCursor cursor, int limit) {
        if (conceptType == null || status == null) throw new IllegalArgumentException("conceptType and status are required");
        validateLimit(limit);
        var sql = new StringBuilder("SELECT * FROM " + tableFor(conceptType) + " WHERE concept_type = ? AND status = ?");
        if (cursor != null) sql.append(" AND (updated_at < ? OR (updated_at = ? AND id < ?))");
        sql.append(" ORDER BY updated_at DESC, id DESC LIMIT ?");
        var concepts = new ArrayList<KnowledgeConcept>();
        try (var scope = readConnection(); var statement = scope.connection().prepareStatement(sql.toString())) {
            int parameter = 1;
            statement.setString(parameter++, conceptType.name());
            statement.setString(parameter++, status.storageValue());
            if (cursor != null) {
                Timestamp timestamp = Timestamp.from(cursor.updatedAt());
                statement.setTimestamp(parameter++, timestamp);
                statement.setTimestamp(parameter++, timestamp);
                statement.setString(parameter++, cursor.conceptId());
            }
            statement.setInt(parameter, limit + 1);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) concepts.add(mapConcept(rows));
            }
        } catch (SQLException failure) { throw new KnowledgePersistenceException("Failed to list Wiki for management export", failure); }
        return PageResponse.fromFetched(concepts, limit, concept -> concept.updatedAt() + "|" + concept.id());
    }

    @Override
    public PageResponse<KnowledgeConcept> findPageInNamespace(
            String tenantId,
            KnowledgeNamespaceType namespaceType,
            String namespaceKey,
            KnowledgeConceptType conceptType,
            KnowledgeStatus status,
            KnowledgeConceptCursor cursor,
            int limit
    ) {
        if (namespaceType == null
                || (namespaceType != KnowledgeNamespaceType.COLLECTION
                && namespaceType != KnowledgeNamespaceType.GRAPH)) {
            throw new IllegalArgumentException("namespaceType must be COLLECTION or GRAPH");
        }
        if (namespaceKey == null || namespaceKey.isBlank() || conceptType == null) {
            throw new IllegalArgumentException("namespaceKey and conceptType are required");
        }
        validateLimit(limit);
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM %s
                WHERE tenant_id <=> ? AND user_id IS NULL
                  AND namespace_type = ? AND namespace_key = ? AND concept_type = ?
                """.formatted(tableFor(conceptType)));
        if (status != null) {
            sql.append(" AND status = ?");
        }
        if (cursor != null) {
            sql.append(" AND (updated_at < ? OR (updated_at = ? AND id < ?))");
        }
        sql.append(" ORDER BY updated_at DESC, id DESC LIMIT ?");

        List<KnowledgeConcept> concepts = new ArrayList<>();
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int parameter = 1;
            statement.setString(parameter++, normalizeTenant(tenantId));
            statement.setString(parameter++, namespaceType.name());
            statement.setString(parameter++, namespaceKey.trim());
            statement.setString(parameter++, conceptType.name());
            if (status != null) {
                statement.setString(parameter++, status.storageValue());
            }
            if (cursor != null) {
                Timestamp timestamp = Timestamp.from(cursor.updatedAt());
                statement.setTimestamp(parameter++, timestamp);
                statement.setTimestamp(parameter++, timestamp);
                statement.setString(parameter++, cursor.conceptId());
            }
            statement.setInt(parameter, limit + 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    concepts.add(mapConcept(resultSet));
                }
            }
        } catch (SQLException e) {
            throw new KnowledgePersistenceException(
                    "Failed to list scoped knowledge concepts", e);
        }
        return PageResponse.fromFetched(
                concepts,
                limit,
                concept -> concept.updatedAt() + "|" + concept.id());
    }

    @Override
    public Optional<KnowledgeRevision> findRevisionById(String revisionId) {
        return snapshot(revisionId).map(KnowledgeRevisionSnapshot::revision);
    }

    @Override
    public KnowledgeRevisionSnapshot findSnapshot(String revisionId) {
        return snapshot(revisionId).orElseThrow(() -> new KnowledgePersistenceException("Knowledge version is unavailable: " + revisionId));
    }

    @Override
    public KnowledgeRevisionSnapshot findMetadataSnapshot(String revisionId) {
        return rawSnapshot(revisionId).orElseThrow(() -> new KnowledgePersistenceException("Wiki version is unavailable: " + revisionId));
    }

    private Optional<KnowledgeRevisionSnapshot> snapshot(String revisionId) {
        return restoreDocumentBody(rawSnapshot(revisionId));
    }

    private Optional<KnowledgeRevisionSnapshot> rawSnapshot(String revisionId) {
        String sql = "SELECT snapshot AS data FROM user_preferences WHERE current_revision_id = ? UNION ALL "
                + "SELECT payload AS data FROM knowledge_tasks WHERE task_type = 'vector_index' AND revision_id = ? AND payload IS NOT NULL LIMIT 1";
        try (var scope = readConnection(); var statement = scope.connection().prepareStatement(sql)) {
            statement.setString(1, revisionId); statement.setString(2, revisionId);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) return Optional.of(KnowledgeRevisionSnapshot.fromJson(objectMapper, rows.getString(1)));
            }
        } catch (SQLException e) { throw new KnowledgePersistenceException("Cannot read pending version", e); }
        if (projectionStore == null) return Optional.empty();
        return projectionStore.findRevisionSnapshot(revisionId);
    }

    private Optional<KnowledgeRevisionSnapshot> restoreDocumentBody(Optional<KnowledgeRevisionSnapshot> stored) {
        if (stored.isEmpty() || stored.get().conceptType() != KnowledgeConceptType.SOURCE_DOCUMENT) return stored;
        var snapshot = stored.get();
        if (!snapshot.revision().body().isEmpty()) return stored;
        String revisionId = snapshot.revision().id();
        if (vectorStore == null) throw new KnowledgePersistenceException("Document vector storage is disabled");
        StringBuilder body = new StringBuilder();
        int next = 0;
        int expectedChunks = -1;
        while (true) {
            var chunks = vectorStore.readDocumentWindow(snapshot.collection(), snapshot.revision().conceptId(), revisionId, next, 0, 99);
            for (var chunk : chunks) {
                if (chunk.chunkIndex() != next++) throw new KnowledgePersistenceException("Document version has a missing chunk");
                Object fragment = chunk.metadata().get("canonical_fragment");
                Object total = chunk.metadata().get("total_chunks");
                if (!(fragment instanceof String text) || !(total instanceof Number count))
                    throw new KnowledgePersistenceException("Document version lacks canonical chunk metadata; reindex is required");
                if (expectedChunks < 0) expectedChunks = count.intValue();
                if (expectedChunks != count.intValue()) throw new KnowledgePersistenceException("Document chunk counts disagree");
                body.append(text);
            }
            if (chunks.size() < 100) break;
        }
        if (next == 0 || next != expectedChunks) throw new KnowledgePersistenceException("Document version is not fully indexed");
        return Optional.of(snapshot.withBody(body.toString()));
    }

    @Override
    public void withAuthorityLock(String conceptId, Runnable action) {
        Connection connection = null;
        try {
            connection = connectionProvider.getConnection();
            connection.setAutoCommit(false);
            // ponytail: one concept row stays locked during its vector write; unrelated concepts proceed independently.
            lockConcept(connection, conceptId);
            projectionConnection.set(connection);
            action.run();
            connection.commit();
        } catch (Exception e) {
            rollback(connection);
            if (e instanceof RuntimeException runtime) throw runtime;
            throw new KnowledgePersistenceException("Knowledge projection transaction failed", e);
        } finally {
            projectionConnection.remove();
            close(connection);
        }
    }

    private ReadConnection readConnection() throws SQLException {
        Connection current = projectionConnection.get();
        return current == null ? new ReadConnection(connectionProvider.getConnection(), true)
                : new ReadConnection(current, false);
    }

    private record ReadConnection(Connection connection, boolean owned) implements AutoCloseable {
        @Override public void close() throws SQLException { if (owned) connection.close(); }
    }

    @Override
    public void deleteDocumentContent(String conceptId) {
        var head = findAuthorityById(conceptId).orElseThrow();
        if (head.concept().conceptType() != KnowledgeConceptType.SOURCE_DOCUMENT) return;
        if (head.concept().status() != KnowledgeStatus.DEPRECATED)
            throw new KnowledgePersistenceException("Only deprecated document content can be deleted");
        if (vectorStore == null) throw new KnowledgePersistenceException("Document vector storage is disabled");
        String revisionId = head.concept().currentRevisionId();
        long previousNumber = Long.MAX_VALUE;
        while (revisionId != null) {
            var snapshot = rawSnapshot(revisionId);
            // Catalog deletion happens after all chunk deletions, so an absent version is already cleaned.
            if (snapshot.isEmpty()) break;
            var revision = snapshot.get().revision();
            if (!conceptId.equals(revision.conceptId()) || revision.revisionNumber() >= previousNumber)
                throw new KnowledgePersistenceException("Invalid document deletion version chain");
            previousNumber = revision.revisionNumber();
            vectorStore.deleteDocumentRevision(head.concept().namespaceKey(), conceptId, revisionId);
            revisionId = (String) revision.metadata().get("previousRevisionId");
        }
    }

    private Map<String, Object> decodeRoute(String json) {
        if (json == null) throw new KnowledgePersistenceException("MySQL knowledge route_data is missing");
        try { return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }); }
        catch (java.io.IOException e) { throw new KnowledgePersistenceException("Invalid MySQL route_data", e); }
    }

    private <T> T decode(String json, Class<T> type) {
        try { return objectMapper.readValue(json, type); }
        catch (java.io.IOException e) { throw new KnowledgePersistenceException("Invalid knowledge metadata", e); }
    }

    private String encode(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (java.io.IOException e) { throw new KnowledgePersistenceException("Cannot serialize knowledge metadata", e); }
    }

    private static String tableFor(KnowledgeConceptType type) {
        return type == KnowledgeConceptType.USER_PREFERENCE ? "user_preferences" : "knowledge_metadata";
    }

    @Override
    public PageResponse<KnowledgeRevision> findRevisionPage(String conceptId, Long beforeRevisionNumber, int limit) {
        validateLimit(limit);
        var head = findAuthorityById(conceptId);
        List<KnowledgeRevision> revisions = new ArrayList<>();
        if (head.isPresent() && head.get().currentRevision() != null) {
            String id = head.get().concept().currentRevisionId();
            long previousNumber = Long.MAX_VALUE;
            while (id != null && revisions.size() <= limit) {
                var revision = findSnapshot(id).revision();
                if (!conceptId.equals(revision.conceptId()) || revision.revisionNumber() >= previousNumber)
                    throw new KnowledgePersistenceException("Invalid knowledge version chain");
                previousNumber = revision.revisionNumber();
                if (beforeRevisionNumber == null || revision.revisionNumber() < beforeRevisionNumber) revisions.add(revision);
                id = (String) revision.metadata().get("previousRevisionId");
            }
        }
        return PageResponse.fromFetched(revisions, limit, r -> Long.toString(r.revisionNumber()));
    }

    @Override
    public PageResponse<KnowledgeSource> findSourcePage(String revisionId, KnowledgeSourceCursor cursor, int limit) {
        validateLimit(limit);
        var order = java.util.Comparator.comparing(KnowledgeSource::observedAt)
                .thenComparing(KnowledgeSource::sourceId).thenComparing(v -> v.sourceType().name()).reversed();
        var values = findSnapshot(revisionId).sources().stream().sorted(order).filter(source -> cursor == null
                || order.compare(source, new KnowledgeSource(revisionId, cursor.sourceType(), cursor.sourceId(),
                    "cursor", cursor.observedAt(), cursor.observedAt())) > 0).limit(limit + 1L).toList();
        return PageResponse.fromFetched(values, limit, v -> KnowledgeSourceCursor.from(v).encode());
    }

    @Override
    public PageResponse<KnowledgeVerification> findVerificationPage(String revisionId, KnowledgeVerificationCursor cursor, int limit) {
        validateLimit(limit);
        var values = findSnapshot(revisionId).verifications().stream()
                .sorted(java.util.Comparator.comparing(KnowledgeVerification::verifiedAt).thenComparing(KnowledgeVerification::id).reversed())
                .filter(v -> cursor == null || v.verifiedAt().isBefore(cursor.verifiedAt())
                        || v.verifiedAt().equals(cursor.verifiedAt()) && v.id() < cursor.verificationId())
                .limit(limit + 1L).toList();
        return PageResponse.fromFetched(values, limit, v -> KnowledgeVerificationCursor.from(v).encode());
    }

    @Override
    public PageResponse<KnowledgeLink> findOutgoingLinkPage(
            String fromConceptId,
            KnowledgeLinkCursor cursor,
            int limit
    ) {
        return findLinkPage(fromConceptId, cursor, limit, true);
    }

    @Override
    public PageResponse<KnowledgeLink> findIncomingLinkPage(
            String toConceptId,
            KnowledgeLinkCursor cursor,
            int limit
    ) {
        return findLinkPage(toConceptId, cursor, limit, false);
    }

    private PageResponse<KnowledgeLink> findLinkPage(
            String conceptId,
            KnowledgeLinkCursor cursor,
            int limit,
            boolean outgoing
    ) {
        validateLimit(limit);
        String fixedColumn = outgoing ? "from_concept_id" : "to_concept_id";
        String counterpartColumn = outgoing ? "to_concept_id" : "c.id";
        // ponytail: incoming links scan concept JSON; normalize only if measured graph size requires it.
        StringBuilder sql = new StringBuilder("""
                SELECT c.id AS from_concept_id, l.* FROM %s c,
                JSON_TABLE(COALESCE(c.links, JSON_ARRAY()), '$[*]' COLUMNS (
                    to_concept_id VARCHAR(64) PATH '$.to_concept_id',
                    link_type VARCHAR(32) PATH '$.link_type',
                    created_at DATETIME(3) PATH '$.created_at'
                )) l WHERE
                """.formatted(HEADS)).append(outgoing ? "c.id" : fixedColumn).append(" = ?");
        if (cursor != null) {
            if (outgoing) {
                sql.append(" AND (").append(counterpartColumn)
                        .append(" > ? OR (").append(counterpartColumn)
                        .append(" = ? AND link_type > ?))");
            } else {
                sql.append(" AND (link_type > ? OR (link_type = ? AND ")
                        .append(counterpartColumn).append(" > ?))");
            }
        }
        if (outgoing) {
            sql.append(" ORDER BY ").append(counterpartColumn).append(", link_type LIMIT ?");
        } else {
            sql.append(" ORDER BY link_type, ").append(counterpartColumn).append(" LIMIT ?");
        }
        List<KnowledgeLink> links = new ArrayList<>();
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int parameter = 1;
            statement.setString(parameter++, conceptId);
            if (cursor != null) {
                if (outgoing) {
                    statement.setString(parameter++, cursor.counterpartConceptId());
                    statement.setString(parameter++, cursor.counterpartConceptId());
                    statement.setString(parameter++, cursor.linkType().name());
                } else {
                    statement.setString(parameter++, cursor.linkType().name());
                    statement.setString(parameter++, cursor.linkType().name());
                    statement.setString(parameter++, cursor.counterpartConceptId());
                }
            }
            statement.setInt(parameter, limit + 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    links.add(mapLink(resultSet));
                }
            }
        } catch (SQLException e) {
            throw new KnowledgePersistenceException(
                    "Failed to list Concept links for " + conceptId, e);
        }
        return PageResponse.fromFetched(
                links,
                limit,
                link -> (outgoing
                        ? KnowledgeLinkCursor.outgoing(link)
                        : KnowledgeLinkCursor.incoming(link)).encode());
    }

    @Override
    public boolean isSourceReferenced(KnowledgeSourceType sourceType, String sourceId) {
        String sql = "SELECT 1 FROM user_preferences WHERE JSON_CONTAINS(snapshot, JSON_QUOTE(?), '$.sourceKeys') "
                + "UNION ALL SELECT 1 FROM knowledge_tasks WHERE task_type = 'vector_index' AND payload IS NOT NULL "
                + "AND JSON_CONTAINS(payload, JSON_QUOTE(?), '$.sourceKeys') LIMIT 1";
        try (Connection c = connectionProvider.getConnection(); var statement = c.prepareStatement(sql)) {
            statement.setString(1, sourceType.name() + ":" + sourceId); statement.setString(2, sourceType.name() + ":" + sourceId);
            try (var rows = statement.executeQuery()) { if (rows.next()) return true; }
        } catch (SQLException e) { throw new KnowledgePersistenceException("Cannot check pending knowledge sources", e); }
        return projectionStore != null && projectionStore.isSourceReferenced(sourceType.name(), sourceId);
    }

    @Override
    public void commitChanges(List<KnowledgeRevisionChange> changes) {
        List<KnowledgeRevisionChange> immutableChanges = List.copyOf(
                changes == null ? List.of() : changes);
        if (immutableChanges.isEmpty()) {
            throw new IllegalArgumentException("changes must not be empty");
        }
        Connection current = projectionConnection.get();
        if (current != null) {
            try {
                for (KnowledgeRevisionChange change : immutableChanges) prepareConceptChange(current, change);
                for (KnowledgeRevisionChange change : immutableChanges) persistRevisionChange(current, change);
                return;
            } catch (SQLException failure) {
                throw new KnowledgePersistenceException("Cannot save Knowledge changes in the current transaction", failure);
            }
        }
        Connection connection = null;
        try {
            connection = connectionProvider.getConnection();
            connection.setAutoCommit(false);
            for (KnowledgeRevisionChange change : immutableChanges) {
                prepareConceptChange(connection, change);
            }
            for (KnowledgeRevisionChange change : immutableChanges) {
                persistRevisionChange(connection, change);
            }
            connection.commit();
        } catch (Exception e) {
            rollback(connection);
            if (e instanceof KnowledgePersistenceException persistenceException) {
                throw persistenceException;
            }
            throw new KnowledgePersistenceException("Failed to commit Knowledge changes", e);
        } finally {
            close(connection);
        }
    }

    void commitRevisionChange(Connection connection, KnowledgeRevisionChange change)
            throws SQLException {
        prepareConceptChange(connection, change);
        persistRevisionChange(connection, change);
    }

    private void prepareConceptChange(Connection connection, KnowledgeRevisionChange change)
            throws SQLException {
        validateConcept(change.concept());
        if (change.concept().conceptType() == KnowledgeConceptType.USER_PREFERENCE && !change.indexTasks().isEmpty()) {
            throw new KnowledgePersistenceException(
                    "Long-term memory Concepts must not create vector index tasks");
        }
        if (change.revision().revisionNumber() != change.expectedConceptVersion() + 1) {
            throw new KnowledgePersistenceException(
                    "Revision number must follow expected Concept version");
        }
        Optional<KnowledgeConcept> stored = lockConcept(connection, change.concept().id());
        if (stored.isEmpty()) {
            if (change.expectedConceptVersion() != 0) {
                throw new KnowledgePersistenceException("New Concept must expect version 0");
            }
            insertConceptShell(connection, change.concept());
        } else {
            verifyConceptIdentity(stored.get(), change.concept());
            if (stored.get().version() != change.expectedConceptVersion()) {
                throw new KnowledgePersistenceException(
                        "Knowledge Concept optimistic lock conflict: " + change.concept().id());
            }
        }
    }

    private void persistRevisionChange(Connection connection, KnowledgeRevisionChange change) throws SQLException {
        var r = change.revision();
        var metadata = new LinkedHashMap<>(r.metadata());
        var previous = lockConcept(connection, change.concept().id()).orElseThrow();
        if (previous.currentRevisionId() != null && change.concept().conceptType() != KnowledgeConceptType.USER_PREFERENCE)
            metadata.put("previousRevisionId", previous.currentRevisionId());
        var revision = new KnowledgeRevision(r.id(), r.conceptId(), r.revisionNumber(), r.title(), r.description(),
                r.body(), r.generatedBy(), r.generatedAt(), r.contentHash(), metadata, r.createdAt());
        var snapshot = new KnowledgeRevisionSnapshot(change.concept().conceptType(), change.concept().namespaceKey(),
                revision, change.sources(), change.verifications());
        String table = tableFor(change.concept().conceptType());
        boolean preference = change.concept().conceptType() == KnowledgeConceptType.USER_PREFERENCE;
        Map<String, Object> routing = new LinkedHashMap<>();
        for (String key : List.of("graphId", "schemaId", "previousRevisionId"))
            if (metadata.containsKey(key)) routing.put(key, metadata.get(key));
        var header = new KnowledgeRevision(r.id(), r.conceptId(), r.revisionNumber(), r.conceptId(), null, "",
                r.generatedBy(), r.generatedAt(), r.contentHash(), routing, r.createdAt());
        String sql = "UPDATE " + table + " SET revision_metadata = CAST(? AS JSON)"
                + (preference ? ", snapshot = CAST(? AS JSON)" : ", route_type = ?, route_data = CAST(? AS JSON)") + " WHERE id = ?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, encode(header));
            int i = 2;
            if (preference) statement.setString(i++, snapshot.toJson(objectMapper));
            else {
                var route = new KnowledgeHead(change.concept(), revision);
                switch (route.routeType()) {
                    case DOCUMENT -> { route.routeText("collectionKey"); route.routeText("documentId"); }
                    case USER_MEMORY, OPERATION_MEMORY -> route.routeText("memoryId");
                    case GRAPH -> {
                        route.routeText("schemaId");
                        if (change.concept().conceptType() == KnowledgeConceptType.GRAPH_SPACE) route.routeText("graphId");
                    }
                }
                statement.setString(i++, route.routeType().name());
                statement.setString(i++, encode(route.routeData()));
            }
            statement.setString(i, r.conceptId());
            if (statement.executeUpdate() != 1) throw new SQLException("Knowledge header update failed");
        }
        for (var link : change.links()) insertLink(connection, link);
        if (!preference) {
            var tasks = change.indexTasks().isEmpty() ? List.of(new KnowledgeIndexTask(null, r.conceptId(), r.id(),
                    KnowledgeIndexOperation.UPSERT_CURRENT, KnowledgeIndexTaskStatus.PENDING, 0,
                    r.createdAt(), null, null, null, r.createdAt())) : change.indexTasks();
            for (var task : tasks) insertOutbox(connection, task);
            try (var statement = connection.prepareStatement("UPDATE knowledge_tasks SET payload = CAST(? AS JSON) "
                    + "WHERE task_type = 'vector_index' AND concept_id = ? AND revision_id = ?")) {
                statement.setString(1, snapshot.toJson(objectMapper)); statement.setString(2, r.conceptId()); statement.setString(3, r.id());
                statement.executeUpdate();
            }
        }
        updateConceptHead(connection, change);
    }

    private void insertConceptShell(Connection connection, KnowledgeConcept concept) throws SQLException {
        String sql = """
                INSERT INTO %s
                    (id, tenant_id, user_id, namespace_type, namespace_key, concept_type,
                     logical_key, status, %s, version, stale_after,
                     created_at, updated_at%s)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'draft', NULL, 0, NULL, ?, ?%s)
                """.formatted(tableFor(concept.conceptType()),
                        concept.conceptType() == KnowledgeConceptType.USER_PREFERENCE ? "current_revision_id" : "current_version",
                        concept.conceptType() == KnowledgeConceptType.USER_PREFERENCE ? "" : ", route_type, route_data",
                        concept.conceptType() == KnowledgeConceptType.USER_PREFERENCE ? "" : ", ?, JSON_OBJECT()");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, concept.id());
            statement.setString(2, normalizeTenant(concept.tenantId()));
            statement.setString(3, normalizeOptional(concept.userId()));
            statement.setString(4, concept.namespaceType().name());
            statement.setString(5, concept.namespaceKey());
            statement.setString(6, concept.conceptType().name());
            statement.setString(7, concept.logicalKey());
            statement.setTimestamp(8, Timestamp.from(concept.createdAt()));
            statement.setTimestamp(9, Timestamp.from(concept.updatedAt()));
            if (concept.conceptType() != KnowledgeConceptType.USER_PREFERENCE)
                statement.setString(10, KnowledgeHead.routeTypeFor(concept.conceptType()).name());
            statement.executeUpdate();
        }
    }

    private void insertLink(Connection connection, KnowledgeLink link) throws SQLException {
        String table = tableFor(lockConcept(connection, link.fromConceptId()).orElseThrow().conceptType());
        String sql = """
                UPDATE %s SET links = JSON_ARRAY_APPEND(
                    COALESCE(links, JSON_ARRAY()), '$', JSON_OBJECT(
                        'to_concept_id', ?, 'link_type', ?, 'created_at', ?))
                WHERE id = ? AND NOT JSON_CONTAINS(COALESCE(links, JSON_ARRAY()),
                    JSON_OBJECT('to_concept_id', ?, 'link_type', ?))
                """.formatted(table);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, link.toConceptId());
            statement.setString(2, link.linkType().name());
            statement.setTimestamp(3, Timestamp.from(link.createdAt()));
            statement.setString(4, link.fromConceptId());
            statement.setString(5, link.toConceptId());
            statement.setString(6, link.linkType().name());
            statement.executeUpdate();
        }
    }

    private static void insertOutbox(Connection connection, KnowledgeIndexTask task)
            throws SQLException {
        String sql = """
                INSERT INTO knowledge_tasks
                    (task_type, concept_id, revision_id, operation, status, attempts,
                     available_at, claimed_at, completed_at, error_message, created_at)
                VALUES ('vector_index', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, task.conceptId());
            statement.setString(2, task.revisionId());
            statement.setString(3, task.operation().name());
            statement.setString(4, task.status().storageValue());
            statement.setInt(5, task.attempts());
            MysqlKnowledgeArtifactRepository.setInstant(statement, 6, task.availableAt());
            MysqlKnowledgeArtifactRepository.setInstant(statement, 7, task.claimedAt());
            MysqlKnowledgeArtifactRepository.setInstant(statement, 8, task.completedAt());
            statement.setString(9, task.errorMessage());
            statement.setTimestamp(10, Timestamp.from(task.createdAt()));
            statement.executeUpdate();
        }
    }

    private static void updateConceptHead(
            Connection connection,
            KnowledgeRevisionChange change
    ) throws SQLException {
        String sql = """
                UPDATE %s
                SET %s = ?, version = version + 1, status = ?,
                    stale_after = ?, updated_at = ?
                WHERE id = ? AND version = ?
                """.formatted(tableFor(change.concept().conceptType()),
                        change.concept().conceptType() == KnowledgeConceptType.USER_PREFERENCE ? "current_revision_id" : "current_version");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, change.revision().id());
            statement.setString(2, change.concept().status().storageValue());
            MysqlKnowledgeArtifactRepository.setInstant(statement, 3, change.concept().staleAfter());
            statement.setTimestamp(4, Timestamp.from(change.concept().updatedAt()));
            statement.setString(5, change.concept().id());
            statement.setLong(6, change.expectedConceptVersion());
            if (statement.executeUpdate() != 1) {
                throw new KnowledgePersistenceException(
                        "Knowledge Concept optimistic lock conflict: " + change.concept().id());
            }
        }
    }

    private Optional<KnowledgeConcept> lockConcept(Connection connection, String conceptId) throws SQLException {
        for (String table : List.of("knowledge_metadata", "user_preferences")) {
            try (var statement = connection.prepareStatement("SELECT * FROM " + table + " WHERE id = ? FOR UPDATE")) {
                statement.setString(1, conceptId);
                try (var rows = statement.executeQuery()) { if (rows.next()) return Optional.of(mapConcept(rows)); }
            }
        }
        return Optional.empty();
    }

    private static void validateConcept(KnowledgeConcept concept) {
        switch (concept.conceptType()) {
            case USER_PREFERENCE -> {
                if (concept.namespaceType() != KnowledgeNamespaceType.USER_MEMORY) {
                    throw new KnowledgePersistenceException(
                            "User Preference must use USER_MEMORY namespace");
                }
                String expectedId = KnowledgeIdentity.preferenceConceptId(
                        concept.tenantId(), concept.userId(), concept.logicalKey());
                if (!expectedId.equals(concept.id())) {
                    throw new KnowledgePersistenceException(
                            "User Preference Concept ID is not deterministic");
                }
            }
            case USER_EPISODE -> {
                if (concept.namespaceType() != KnowledgeNamespaceType.USER_MEMORY) {
                    throw new KnowledgePersistenceException(
                            "User Episode must use USER_MEMORY namespace");
                }
            }
            case OPERATION_PLAYBOOK -> {
                if (concept.namespaceType() != KnowledgeNamespaceType.OPERATION_MEMORY) {
                    throw new KnowledgePersistenceException(
                            "Operation Playbook must use OPERATION_MEMORY namespace");
                }
            }
            case SOURCE_DOCUMENT -> {
                if (concept.namespaceType() != KnowledgeNamespaceType.COLLECTION) {
                    throw new KnowledgePersistenceException(
                            "Source Document must use COLLECTION namespace");
                }
            }
            case GRAPH_SCHEMA, GRAPH_SPACE -> {
                if (concept.namespaceType() != KnowledgeNamespaceType.GRAPH) {
                    throw new KnowledgePersistenceException(
                            "Graph Concept must use GRAPH namespace");
                }
            }
        }
    }

    private static void verifyConceptIdentity(KnowledgeConcept stored, KnowledgeConcept requested) {
        if (!sameTenant(stored.tenantId(), requested.tenantId())
                || !java.util.Objects.equals(stored.userId(), requested.userId())
                || stored.namespaceType() != requested.namespaceType()
                || !java.util.Objects.equals(stored.namespaceKey(), requested.namespaceKey())
                || stored.conceptType() != requested.conceptType()
                || !java.util.Objects.equals(stored.logicalKey(), requested.logicalKey())) {
            throw new KnowledgePersistenceException(
                    "Concept ID is already bound to a different identity or scope");
        }
    }

    private static KnowledgeConcept mapConcept(ResultSet resultSet) throws SQLException {
        return mapConcept(resultSet, KnowledgeConceptType.USER_PREFERENCE.name().equals(resultSet.getString("concept_type"))
                ? "current_revision_id" : "current_version");
    }

    private static KnowledgeConcept mapConcept(ResultSet resultSet, String versionColumn) throws SQLException {
        return new KnowledgeConcept(
                resultSet.getString("id"),
                resultSet.getString("tenant_id"),
                resultSet.getString("user_id"),
                KnowledgeNamespaceType.valueOf(resultSet.getString("namespace_type")),
                resultSet.getString("namespace_key"),
                KnowledgeConceptType.valueOf(resultSet.getString("concept_type")),
                resultSet.getString("logical_key"),
                KnowledgeStatus.valueOf(
                        resultSet.getString("status").toUpperCase(java.util.Locale.ROOT)),
                resultSet.getString(versionColumn),
                resultSet.getLong("version"),
                instant(resultSet, "stale_after"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"));
    }

    private static KnowledgeLink mapLink(ResultSet resultSet) throws SQLException {
        return new KnowledgeLink(
                resultSet.getString("from_concept_id"),
                resultSet.getString("to_concept_id"),
                KnowledgeLinkType.valueOf(resultSet.getString("link_type")),
                instant(resultSet, "created_at"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static boolean sameTenant(String left, String right) {
        return java.util.Objects.equals(normalizeTenant(left), normalizeTenant(right));
    }

    private static String normalizeTenant(String tenantId) {
        return normalizeOptional(tenantId);
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
    }

    private static void rollback(Connection connection) {
        if (connection != null) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // Preserve original failure.
            }
        }
    }

    private static void close(Connection connection) {
        if (connection != null) {
            try {
                connection.setAutoCommit(true);
                connection.close();
            } catch (SQLException ignored) {
                // Preserve original failure.
            }
        }
    }
}

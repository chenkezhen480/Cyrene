package com.harness.graph.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.model.GraphRequestContext;
import com.harness.core.model.PageResponse;
import com.harness.graph.config.GraphSettings;
import com.harness.graph.model.*;
import com.harness.graph.schema.GraphSchemaRegistry;
import com.harness.graph.schema.GraphSchemaValidator;
import com.harness.graph.store.KnowledgeGraphStore;
import com.harness.tool.artifact.ArtifactStorageService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Single-instance draft chain: immutable artifacts plus atomically published current references. */
public final class GraphChangeDraftService {
    private final ArtifactStorageService artifacts;
    private final KnowledgeGraphStore store;
    private final GraphSchemaRegistry schemas;
    private final CanonicalJsonGraphDataConverter converter;
    private final GraphMutationCommitter committer;
    private final GraphDraftAccess access;
    private final ObjectMapper mapper;
    private final GraphSettings settings;
    private final GraphSchemaValidator schemaValidator;

    public GraphChangeDraftService(ArtifactStorageService artifacts, KnowledgeGraphStore store,
            GraphSchemaRegistry schemas, CanonicalJsonGraphDataConverter converter,
            GraphMutationCommitter committer, GraphDraftAccess access, ObjectMapper mapper) {
        this.artifacts = Objects.requireNonNull(artifacts);
        this.store = Objects.requireNonNull(store);
        this.schemas = Objects.requireNonNull(schemas);
        this.converter = Objects.requireNonNull(converter);
        this.committer = Objects.requireNonNull(committer);
        this.access = Objects.requireNonNull(access);
        this.mapper = Objects.requireNonNull(mapper);
        this.settings = GraphSettings.fromEnvironment();
        this.schemaValidator = new GraphSchemaValidator(schemas);
    }

    public synchronized GraphDraftView prepare(GraphDraftScope scope, GraphDraftPrepareRequest request) {
        Objects.requireNonNull(request, "request");
        Draft previous = null;
        String graphId = request.graphId();
        String schemaId = request.schemaId();
        String rootDraftId = UUID.randomUUID().toString();
        if (request.sourceDraftId() != null) {
            Loaded loaded = load(scope, request.sourceDraftId(), request.expectedSourceContentHash(), true);
            if (!editable(loaded.current)) throw conflict("Draft cannot be edited during or after application");
            previous = loaded.draft;
            assertBaseline(previous);
            graphId = fixedTarget(graphId, previous.graphId, "graphId");
            schemaId = fixedTarget(schemaId, previous.schemaId, "schemaId");
            rootDraftId = previous.rootDraftId;
        } else if (request.expectedSourceContentHash() != null) {
            throw new IllegalArgumentException("sourceDraftId is required with expectedSourceContentHash");
        }
        GraphDraftScope.require(graphId, "graphId");
        GraphDraftScope.require(schemaId, "schemaId");
        GraphDraftScope.require(scope.sessionId(), "sessionId");
        GraphDraftScope.require(scope.runId(), "runId");
        GraphDraftScope.require(scope.traceId(), "traceId");
        requireTarget(scope, graphId, schemaId);
        access.requireReadable(scope, graphId, schemaId);
        String schemaHash = GraphContentHash.of(schemas.require(schemaId), mapper);
        Map<String, GraphNode> nodes = new TreeMap<>();
        Map<String, GraphRelation> relations = new TreeMap<>();
        Set<String> deleteNodes = new TreeSet<>();
        Set<String> deleteRelations = new TreeSet<>();
        Set<String> explicitDeleteRelations = new TreeSet<>();
        Map<String, GraphNode> beforeNodes = new TreeMap<>();
        Map<String, GraphRelation> beforeRelations = new TreeMap<>();
        Map<String, Set<String>> incident = new TreeMap<>();
        if (previous != null) {
            nodes.putAll(previous.nodes); relations.putAll(previous.relations);
            deleteNodes.addAll(previous.deleteNodeIds); deleteRelations.addAll(previous.deleteRelationIds);
            explicitDeleteRelations.addAll(previous.explicitDeleteRelationIds);
            beforeNodes.putAll(previous.baseline.nodes()); beforeRelations.putAll(previous.baseline.relations());
            incident.putAll(previous.baseline.incidentRelationIds());
        }
        for (String changeId : new TreeSet<>(request.discardChangeIds())) {
            if (changeId.startsWith("node:")) {
                String id = changeId.substring(5);
                if (nodes.remove(id) == null && !deleteNodes.contains(id)) throw new IllegalArgumentException("Unknown change: " + changeId);
                deleteNodes.remove(id);
                restoreIncidentDeletion(id, incident, deleteRelations, explicitDeleteRelations);
            } else if (changeId.startsWith("relation:")) {
                String id = changeId.substring(9);
                if (incident.values().stream().anyMatch(ids -> ids.contains(id)))
                    throw new IllegalArgumentException("Relation deletion is required by a pending node deletion: " + id);
                if (relations.remove(id) == null && !deleteRelations.contains(id)) throw new IllegalArgumentException("Unknown change: " + changeId);
                deleteRelations.remove(id);
                explicitDeleteRelations.remove(id);
            } else throw new IllegalArgumentException("Invalid changeId: " + changeId);
        }
        Set<String> deltaNodeIds = new HashSet<>();
        Set<String> deltaRelationIds = new HashSet<>();
        JsonNode nodePatches = array(request.nodes(), "nodes");
        JsonNode relationPatches = array(request.relations(), "relations");
        for (JsonNode patch : nodePatches) {
            String id = identity(patch, "nodeId");
            if (!deltaNodeIds.add(id) || request.deleteNodeIds().contains(id)) throw new IllegalArgumentException("Contradictory node changes: " + id);
            if (deleteNodes.contains(id)) throw new IllegalArgumentException("Pending node deletion requires explicit discard before modification: " + id);
            requireSubject(scope, id);
            captureNode(beforeNodes, graphId, schemaId, id);
            GraphNode existing = nodes.get(id);
            if (existing == null) existing = beforeNodes.get(id);
            GraphNode merged = convertNode(mergeProperties(existing, patch));
            nodes.put(id, merged);
        }
        for (JsonNode patch : relationPatches) {
            String id = identity(patch, "relationId");
            if (!deltaRelationIds.add(id) || request.deleteRelationIds().contains(id)) throw new IllegalArgumentException("Contradictory relation changes: " + id);
            if (deleteRelations.contains(id)) throw new IllegalArgumentException("Pending relation deletion requires explicit discard before modification: " + id);
            captureRelation(beforeRelations, graphId, schemaId, id);
            requireRelationSubjects(scope, beforeRelations.get(id));
            GraphRelation existing = relations.get(id);
            if (existing == null) existing = beforeRelations.get(id);
            GraphRelation merged = convertRelation(mergeProperties(existing, patch));
            requireRelationSubjects(scope, merged);
            relations.put(id, merged);
        }
        for (String id : request.deleteRelationIds()) {
            GraphDraftScope.require(id, "deleteRelationId");
            captureRelation(beforeRelations, graphId, schemaId, id);
            GraphRelation before = beforeRelations.get(id);
            if (before != null) {
                requireSubject(scope, before.sourceNodeId()); requireSubject(scope, before.targetNodeId());
                deleteRelations.add(id); explicitDeleteRelations.add(id);
            }
            relations.remove(id);
        }
        for (String id : request.deleteNodeIds()) {
            GraphDraftScope.require(id, "deleteNodeId"); requireSubject(scope, id);
            captureNode(beforeNodes, graphId, schemaId, id);
            nodes.remove(id);
            if (beforeNodes.get(id) != null) {
                deleteNodes.add(id);
                if (!incident.containsKey(id)) {
                    List<GraphRelation> connected = incidentRelations(graphId, schemaId, id);
                    Set<String> ids = new TreeSet<>();
                    for (GraphRelation relation : connected) {
                        requireSubject(scope, relation.sourceNodeId()); requireSubject(scope, relation.targetNodeId());
                        ids.add(relation.relationId());
                        beforeRelations.putIfAbsent(relation.relationId(), relation);
                    }
                    incident.put(id, Set.copyOf(ids));
                }
                deleteRelations.addAll(incident.get(id));
            } else deleteNodes.remove(id);
            List<String> newRelations = relations.values().stream()
                    .filter(relation -> id.equals(relation.sourceNodeId()) || id.equals(relation.targetNodeId()))
                    .map(GraphRelation::relationId).toList();
            for (String relationId : newRelations) {
                if (deltaRelationIds.contains(relationId)) throw new IllegalArgumentException("Relation endpoint is deleted: " + id);
                relations.remove(relationId);
                if (beforeRelations.get(relationId) != null) deleteRelations.add(relationId);
            }
        }
        relations.keySet().removeAll(deleteRelations);
        Set<String> neededNodeIds = new TreeSet<>(nodes.keySet()); neededNodeIds.addAll(deleteNodes);
        Set<String> neededRelationIds = new TreeSet<>(relations.keySet()); neededRelationIds.addAll(deleteRelations);
        for (GraphRelation relation : relations.values()) {
            for (String id : List.of(relation.sourceNodeId(), relation.targetNodeId())) {
                if (deleteNodes.contains(id)) throw new IllegalArgumentException("Relation endpoint is deleted: " + id);
                captureNode(beforeNodes, graphId, schemaId, id); neededNodeIds.add(id);
                if (!nodes.containsKey(id) && beforeNodes.get(id) == null) throw new IllegalArgumentException("Relation endpoint does not exist: " + id);
            }
        }
        for (String relationId : deleteRelations) {
            GraphRelation relation = beforeRelations.get(relationId);
            if (relation != null) for (String id : List.of(relation.sourceNodeId(), relation.targetNodeId())) {
                captureNode(beforeNodes, graphId, schemaId, id); neededNodeIds.add(id);
            }
        }
        beforeNodes.keySet().retainAll(neededNodeIds); beforeRelations.keySet().retainAll(neededRelationIds);
        incident.keySet().retainAll(deleteNodes);
        if (neededNodeIds.size() + neededRelationIds.size() > settings.contextMaxItems())
            throw new IllegalArgumentException("Draft exceeds configured graph item limit");
        validateEffective(schemaId, nodes, relations, beforeNodes);
        if (previous == null && nodes.isEmpty() && relations.isEmpty() && deleteNodes.isEmpty() && deleteRelations.isEmpty())
            throw new IllegalArgumentException("Initial graph draft must contain at least one effective change");
        String draftId = UUID.randomUUID().toString();
        Draft draft = new Draft(rootDraftId, draftId, request.sourceDraftId(), UUID.randomUUID().toString(),
                graphId, schemaId, previous == null ? scope : previous.owner, Instant.now().toString(),
                nodes, relations, deleteNodes, deleteRelations, explicitDeleteRelations,
                new GraphMutationBaseline(schemaHash, beforeNodes, beforeRelations, incident));
        byte[] content = write(draft);
        String hash = GraphContentHash.bytes(content);
        artifacts.writeGraphDraftPayload(draftId, content);
        Reference version = new Reference(rootDraftId, draftId, draftId, hash, scope.sessionId(), "PENDING", null, null);
        artifacts.writeGraphDraftReference(draftId, write(version));
        artifacts.writeGraphDraftReference(rootDraftId, write(version));
        return view(draft, version);
    }

    public synchronized GraphDraftView read(GraphDraftScope scope, String draftId, String expectedContentHash) {
        Loaded loaded = load(scope, draftId, expectedContentHash, false);
        return view(loaded.draft, loaded.current);
    }

    public synchronized PageResponse<GraphDraftPreviewItem> readPreview(GraphDraftScope scope,
            String draftId, String expectedContentHash, int limit, String cursor) {
        Loaded loaded = load(scope, draftId, expectedContentHash, true);
        if (loaded.current.status.equals("PENDING")) assertBaseline(loaded.draft);
        int pageLimit = settings.capLimit(limit);
        String last = "";
        if (cursor != null && !cursor.isBlank()) {
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                String prefix = expectedContentHash + ":";
                if (!decoded.startsWith(prefix)) throw conflict("Preview cursor belongs to another draft version");
                last = decoded.substring(prefix.length());
            } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid draft cursor", e); }
        }
        List<GraphDraftPreviewItem> preview = preview(loaded.draft, loaded.current.contentHash);
        String after = last;
        List<GraphDraftPreviewItem> fetched = preview.stream().filter(item -> item.changeId().compareTo(after) > 0)
                .limit((long) pageLimit + 1).toList();
        return PageResponse.fromFetched(fetched, pageLimit, item -> Base64.getUrlEncoder().withoutPadding()
                .encodeToString((expectedContentHash + ":" + item.changeId()).getBytes(StandardCharsets.UTF_8)));
    }

    public synchronized GraphMutationResult apply(GraphDraftScope reviewer, String draftId, String expectedContentHash) {
        Loaded loaded = load(reviewer, draftId, expectedContentHash, true);
        access.requireWritable(reviewer, loaded.draft.graphId, loaded.draft.schemaId);
        if (loaded.current.status.equals("APPLIED")) return loaded.current.result;
        if (loaded.current.status.equals("FAILED")) throw new IllegalStateException(loaded.current.failure.message());
        Draft draft = loaded.draft;
        GraphChangeSet change = new GraphChangeSet(draft.requestId, draft.graphId, draft.schemaId,
                List.copyOf(draft.nodes.values()), List.copyOf(draft.relations.values()),
                draft.deleteNodeIds, draft.deleteRelationIds, draft.baseline);
        if (loaded.current.status.equals("PENDING")) assertBaseline(draft);
        Reference applying = loaded.current.withStatus("APPLYING", null);
        artifacts.writeGraphDraftReference(draft.rootDraftId, write(applying));
        GraphMutationResult result;
        try { result = committer.commit(change); }
        catch (RuntimeException failure) {
            try { reconcile(draft, applying); }
            catch (RuntimeException persistenceFailure) { failure.addSuppressed(persistenceFailure); }
            throw failure;
        }
        if (!result.committed()) throw new IllegalStateException("Graph mutation was not committed");
        artifacts.writeGraphDraftReference(draft.rootDraftId, write(applying.withStatus("APPLIED", result)));
        return result;
    }

    private Loaded load(GraphDraftScope scope, String draftId, String expectedHash, boolean requireHash) {
        if (requireHash) GraphDraftScope.require(expectedHash, "expectedContentHash");
        Reference version = reference(draftId);
        Draft draft = readDraft(version);
        if (!draft.draftId.equals(draftId)) throw conflict("Draft reference mismatch");
        authorize(scope, draft);
        Reference current = reference(draft.rootDraftId);
        if (!current.rootDraftId.equals(draft.rootDraftId)) throw conflict("Draft current reference mismatch");
        if (!current.draftId.equals(draftId) || !current.contentHash.equals(version.contentHash)) {
            authorize(scope, readDraft(current));
            throw new GraphDraftConflictException("Draft version is no longer current", current.draftId, current.contentHash);
        }
        if (expectedHash != null && !version.contentHash.equals(expectedHash))
            throw new GraphDraftConflictException("Draft content hash changed", current.draftId, current.contentHash);
        if (current.status.equals("APPLYING")) current = reconcile(draft, current);
        return new Loaded(draft, current);
    }

    private Reference reconcile(Draft draft, Reference current) {
        Optional<GraphMutationResult> committed = committer.findCommitted(draft.requestId);
        if (committed.isPresent()) current = current.withStatus("APPLIED", committed.get());
        else {
            Optional<GraphMutationCommitter.Failure> failed = committer.findFailure(draft.requestId);
            if (failed.isEmpty()) return current;
            current = current.withFailure(failed.get());
        }
        artifacts.writeGraphDraftReference(draft.rootDraftId, write(current));
        return current;
    }

    private static boolean editable(Reference reference) {
        return reference.status.equals("PENDING") || reference.status.equals("FAILED")
                && reference.failure != null && !reference.failure.graphCommitted();
    }

    private Draft readDraft(Reference reference) {
        if (reference.payloadId == null || !reference.payloadId.equals(reference.draftId)) throw conflict("Invalid internal graph draft payload reference");
        byte[] content = artifacts.readGraphDraftPayload(reference.payloadId);
        if (!GraphContentHash.bytes(content).equals(reference.contentHash)) throw conflict("Draft payload content hash mismatch");
        Draft draft = read(content, Draft.class);
        if (!draft.draftId.equals(reference.draftId) || !draft.rootDraftId.equals(reference.rootDraftId)) throw conflict("Draft reference mismatch");
        return draft;
    }

    private void authorize(GraphDraftScope scope, Draft draft) {
        if (!Objects.equals(scope.userId(), draft.owner.userId()) || !Objects.equals(scope.tenantId(), draft.owner.tenantId()))
            throw new SecurityException("Draft belongs to another user or tenant");
        if (scope.sessionId() != null && !Objects.equals(scope.sessionId(), draft.owner.sessionId()))
            throw new SecurityException("Draft belongs to another session");
        if (scope.taskId() != null && !Objects.equals(scope.taskId(), draft.owner.taskId()))
            throw new SecurityException("Draft belongs to another task");
        requireTarget(scope, draft.graphId, draft.schemaId);
        draft.baseline.nodes().keySet().forEach(id -> requireSubject(scope, id));
        draft.baseline.relations().values().forEach(relation -> requireRelationSubjects(scope, relation));
        draft.relations.values().forEach(relation -> requireRelationSubjects(scope, relation));
        access.requireReadable(scope, draft.graphId, draft.schemaId);
    }

    private void assertBaseline(Draft draft) {
        if (!GraphContentHash.of(schemas.require(draft.schemaId), mapper).equals(draft.baseline.schemaHash())) throw conflict("Graph Schema baseline changed");
        draft.baseline.nodes().forEach((id, before) -> {
            if (!same(before, store.getNode(new GraphNodeKey(draft.graphId, draft.schemaId, id)))) throw conflict("Node baseline changed: " + id);
        });
        draft.baseline.relations().forEach((id, before) -> {
            if (!same(before, store.getRelation(draft.graphId, draft.schemaId, id))) throw conflict("Relation baseline changed: " + id);
        });
        draft.baseline.incidentRelationIds().forEach((id, before) -> {
            Set<String> current = new HashSet<>();
            incidentRelations(draft.graphId, draft.schemaId, id).forEach(relation -> current.add(relation.relationId()));
            if (!before.equals(current)) throw conflict("Node relation baseline changed: " + id);
        });
    }

    private boolean same(Object left, Object right) { return GraphContentHash.of(left, mapper).equals(GraphContentHash.of(right, mapper)); }

    private void validateEffective(String schemaId, Map<String, GraphNode> nodes,
            Map<String, GraphRelation> relations, Map<String, GraphNode> before) {
        if (nodes.isEmpty() && relations.isEmpty()) return;
        Map<String, GraphNode> effective = new TreeMap<>(nodes);
        for (GraphRelation relation : relations.values()) {
            effective.putIfAbsent(relation.sourceNodeId(), before.get(relation.sourceNodeId()));
            effective.putIfAbsent(relation.targetNodeId(), before.get(relation.targetNodeId()));
        }
        schemaValidator.validate(new GraphMutationBatch("validate-draft", "validate-draft", schemaId,
                List.copyOf(effective.values()), List.copyOf(relations.values())));
    }

    private List<GraphRelation> incidentRelations(String graphId, String schemaId, String id) {
        List<GraphRelation> all = new ArrayList<>();
        String cursor = "";
        do {
            PageResponse<GraphRelation> page = store.listIncidentRelations(graphId, schemaId, Set.of(id), settings.defaultLimit(), cursor);
            all.addAll(page.items());
            if (all.size() > settings.contextMaxItems()) throw new IllegalArgumentException("Node deletion exceeds configured graph item limit");
            if (!page.pageInfo().hasMore()) return all;
            if (cursor.equals(page.pageInfo().nextCursor())) throw new IllegalStateException("Graph relation cursor did not advance");
            cursor = page.pageInfo().nextCursor();
        } while (true);
    }

    private List<GraphDraftPreviewItem> preview(Draft draft, String hash) {
        List<GraphDraftPreviewItem> result = new ArrayList<>();
        draft.baseline.nodes().forEach((id, before) -> {
            boolean deleted = draft.deleteNodeIds.contains(id);
            GraphNode after = deleted ? null : draft.nodes.getOrDefault(id, before);
            String operation = deleted ? "DELETE" : draft.nodes.containsKey(id) ? (before == null ? "ADD" : "UPDATE") : "CONTEXT";
            result.add(item(draft, hash, "node:" + id, "NODE", operation, before, after));
        });
        draft.baseline.relations().forEach((id, before) -> {
            boolean deleted = draft.deleteRelationIds.contains(id);
            GraphRelation after = deleted ? null : draft.relations.getOrDefault(id, before);
            result.add(item(draft, hash, "relation:" + id, "RELATION", deleted ? "DELETE" : before == null ? "ADD" : "UPDATE", before, after));
        });
        result.sort(Comparator.comparing(GraphDraftPreviewItem::changeId));
        return result;
    }

    private GraphDraftPreviewItem item(Draft draft, String hash, String id, String entity, String operation, Object before, Object after) {
        return new GraphDraftPreviewItem("DRAFT_PREVIEW", draft.draftId, hash, id, entity, operation,
                visible(draft.schemaId, before), visible(draft.schemaId, after));
    }

    private Object visible(String schemaId, Object entity) {
        var schema = schemas.require(schemaId);
        if (entity instanceof GraphNode node) {
            Map<String, Object> properties = new TreeMap<>(node.properties());
            node.labels().forEach(label -> schema.requireNodeType(label).properties().values()
                    .stream().filter(com.harness.graph.schema.GraphPropertyDefinition::sensitive)
                    .forEach(property -> properties.remove(property.name())));
            return new GraphNode(node.nodeId(), node.labels(), properties);
        }
        if (entity instanceof GraphRelation relation) {
            Map<String, Object> properties = new TreeMap<>(relation.properties());
            schema.requireRelationType(relation.relationType()).properties().values()
                    .stream().filter(com.harness.graph.schema.GraphPropertyDefinition::sensitive)
                    .forEach(property -> properties.remove(property.name()));
            return new GraphRelation(relation.relationId(), relation.sourceNodeId(), relation.targetNodeId(), relation.relationType(), properties);
        }
        return entity;
    }

    private ObjectNode mergeProperties(Object existing, JsonNode patch) {
        if (!patch.isObject()) throw new IllegalArgumentException("Graph patch must be an object");
        ObjectNode merged = existing == null ? mapper.createObjectNode() : mapper.valueToTree(existing);
        ObjectNode properties = merged.has("properties") ? (ObjectNode) merged.get("properties") : mapper.createObjectNode();
        patch.fields().forEachRemaining(entry -> {
            if (!entry.getKey().equals("properties")) merged.set(entry.getKey(), entry.getValue());
        });
        JsonNode patchProperties = patch.get("properties");
        if (patchProperties != null) {
            if (!patchProperties.isObject()) throw new IllegalArgumentException("properties must be an object");
            patchProperties.fields().forEachRemaining(entry -> {
                if (entry.getValue().isNull()) properties.remove(entry.getKey());
                else properties.set(entry.getKey(), entry.getValue());
            });
        }
        merged.set("properties", properties);
        return merged;
    }

    private GraphNode convertNode(JsonNode node) {
        ObjectNode source = mapper.createObjectNode(); source.putArray("nodes").add(node);
        return convert(source).nodes().getFirst();
    }
    private GraphRelation convertRelation(JsonNode relation) {
        ObjectNode source = mapper.createObjectNode(); source.putArray("relations").add(relation);
        return convert(source).relations().getFirst();
    }
    private GraphMutationDraft convert(JsonNode source) {
        return converter.convert(new GraphBuildRequest("draft-conversion", "draft-conversion", "draft-conversion",
                GraphBuildSourceType.STRUCTURED, CanonicalJsonGraphDataConverter.CONVERTER_ID, source));
    }
    private static JsonNode array(JsonNode value, String field) {
        if (value == null || value.isNull()) return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        if (!value.isArray()) throw new IllegalArgumentException(field + " must be an array");
        return value;
    }
    private static String identity(JsonNode patch, String field) {
        JsonNode value = patch.get(field);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException(field + " must be text");
        return GraphDraftScope.require(value.asText(), field);
    }
    private void captureNode(Map<String, GraphNode> baseline, String graphId, String schemaId, String id) {
        if (!baseline.containsKey(id)) baseline.put(id, store.getNode(new GraphNodeKey(graphId, schemaId, id)));
    }
    private void captureRelation(Map<String, GraphRelation> baseline, String graphId, String schemaId, String id) {
        if (!baseline.containsKey(id)) baseline.put(id, store.getRelation(graphId, schemaId, id));
    }
    private static void restoreIncidentDeletion(String nodeId, Map<String, Set<String>> incident,
            Set<String> deleted, Set<String> explicit) {
        Set<String> automaticallyDeleted = incident.remove(nodeId);
        if (automaticallyDeleted != null) automaticallyDeleted.stream()
                .filter(id -> !explicit.contains(id))
                .filter(id -> incident.values().stream().noneMatch(ids -> ids.contains(id)))
                .forEach(deleted::remove);
    }
    private static void requireTarget(GraphDraftScope scope, String graphId, String schemaId) {
        GraphRequestContext bound = scope.graphRequestContext();
        if (bound != null && (!bound.graphId().equals(graphId) || !bound.schemaId().equals(schemaId)))
            throw new SecurityException("Draft target exceeds the trusted graph scope");
    }
    private static void requireSubject(GraphDraftScope scope, String id) {
        if (scope.graphRequestContext() != null && scope.graphRequestContext().hasSubjectScope()
                && !scope.graphRequestContext().subjectIds().contains(id)) throw new SecurityException("Draft node exceeds the trusted subject scope: " + id);
    }
    private static void requireRelationSubjects(GraphDraftScope scope, GraphRelation relation) {
        if (relation != null) {
            requireSubject(scope, relation.sourceNodeId());
            requireSubject(scope, relation.targetNodeId());
        }
    }
    private static String fixedTarget(String requested, String fixed, String field) {
        if (requested != null && !requested.equals(fixed)) throw new SecurityException("A draft cannot switch " + field);
        return fixed;
    }
    private Reference reference(String id) {
        return read(artifacts.readGraphDraftReference(id).orElseThrow(() -> new NoSuchElementException("Graph draft not found: " + id)), Reference.class);
    }
    private <T> T read(byte[] content, Class<T> type) {
        try { return mapper.readValue(content, type); }
        catch (IOException e) { throw new IllegalStateException("Invalid persisted graph draft", e); }
    }
    private byte[] write(Object value) {
        try { return mapper.writeValueAsBytes(value); }
        catch (IOException e) { throw new IllegalStateException("Failed to serialize graph draft", e); }
    }
    private static IllegalStateException conflict(String message) { return new IllegalStateException(message); }
    private static GraphDraftView view(Draft draft, Reference reference) {
        GraphDraftScope owner = draft.owner;
        return new GraphDraftView(draft.rootDraftId, draft.draftId, draft.sourceDraftId, reference.contentHash,
                draft.graphId, draft.schemaId, owner.tenantId(), owner.userId(), owner.sessionId(), owner.runId(), owner.traceId(), owner.taskId(),
                draft.requestId, reference.status, draft.nodes.size(), draft.relations.size(), draft.deleteNodeIds.size(), draft.deleteRelationIds.size(),
                draft.createdAt, "/api/graph/change-drafts/" + draft.draftId, reference.failure);
    }
    private record Draft(String rootDraftId, String draftId, String sourceDraftId, String requestId, String graphId,
                         String schemaId, GraphDraftScope owner, String createdAt, Map<String, GraphNode> nodes,
                         Map<String, GraphRelation> relations, Set<String> deleteNodeIds, Set<String> deleteRelationIds,
                         Set<String> explicitDeleteRelationIds,
                         GraphMutationBaseline baseline) { }
    private record Reference(String rootDraftId, String draftId, String payloadId, String contentHash,
                             String sessionId, String status, GraphMutationResult result, GraphMutationCommitter.Failure failure) {
        Reference withStatus(String status, GraphMutationResult result) {
            return new Reference(rootDraftId, draftId, payloadId, contentHash, sessionId, status, result, null);
        }
        Reference withFailure(GraphMutationCommitter.Failure failure) {
            return new Reference(rootDraftId, draftId, payloadId, contentHash, sessionId, "FAILED", null, failure);
        }
    }
    private record Loaded(Draft draft, Reference current) { }
}

package com.harness.tool.knowledge;

import com.harness.core.knowledge.KnowledgeConcept;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeIdentity;
import com.harness.core.knowledge.KnowledgeIndexOperation;
import com.harness.core.knowledge.KnowledgeIndexTask;
import com.harness.core.knowledge.KnowledgeIndexTaskStatus;
import com.harness.core.knowledge.KnowledgeNamespaceType;
import com.harness.core.knowledge.KnowledgeRevision;
import com.harness.core.knowledge.KnowledgeSource;
import com.harness.core.knowledge.KnowledgeSourceType;
import com.harness.core.knowledge.KnowledgeStatus;
import com.harness.graph.model.GraphChangeSet;
import com.harness.graph.model.GraphMutationResult;
import com.harness.graph.schema.GraphSchemaDefinition;
import com.harness.graph.schema.GraphSchemaRegistry;
import com.harness.tool.knowledge.authority.KnowledgeHead;
import com.harness.tool.knowledge.authority.KnowledgeRepository;
import com.harness.tool.knowledge.authority.KnowledgeRevisionChange;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Writes one small Catalog entry per graphId/schemaId; graph rows remain only in Neo4j.
 *
 * <p>Carries no model-written description: describing what a graph can answer is the Graph Schema
 * card's job, and a space card only records that this concrete space exists under that Schema.</p>
 */
public final class PersistentGraphSpaceWikiCompiler implements GraphSpaceWikiCompiler {

    private static final String COMPILER_ID = "cyrene-graph-space-wiki-compiler/v2";

    private final KnowledgeRepository repository;
    private final GraphSchemaRegistry schemaRegistry;
    private final WikiIdentityResolver identityResolver;

    public PersistentGraphSpaceWikiCompiler(
            KnowledgeRepository repository,
            GraphSchemaRegistry schemaRegistry
    ) {
        this(repository, schemaRegistry, null);
    }

    public PersistentGraphSpaceWikiCompiler(
            KnowledgeRepository repository,
            GraphSchemaRegistry schemaRegistry,
            WikiIdentityResolver identityResolver
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.schemaRegistry = Objects.requireNonNull(schemaRegistry, "schemaRegistry");
        this.identityResolver = identityResolver;
    }

    @Override
    public String synchronize(GraphChangeSet changeSet, GraphMutationResult mutationResult) {
        Objects.requireNonNull(changeSet, "changeSet");
        Objects.requireNonNull(mutationResult, "mutationResult");
        if (!mutationResult.committed()
                || !changeSet.requestId().equals(mutationResult.requestId())) {
            throw new IllegalArgumentException(
                    "Graph Space Wiki requires the matching committed graph mutation");
        }

        String conceptId = conceptId(changeSet.graphId(), changeSet.schemaId());
        KnowledgeHead existing = repository.findById(conceptId).orElse(null);
        GraphSchemaDefinition schema = schemaRegistry.require(changeSet.schemaId());
        String structuralBody = renderBody(changeSet, schema);
        String sourceHash = KnowledgeIdentity.sha256(structuralBody);
        if (existing != null && existing.concept().status() == KnowledgeStatus.STABLE
                && sourceHash.equals(existing.currentRevision().metadata().get("capabilitySourceHash"))) {
            return existing.currentRevision().id();
        }
        // Mechanical, not model-written: this one line is what the Catalog row shows for a space.
        String summary = "Graph Space " + changeSet.graphId() + " on Schema "
                + changeSet.schemaId() + ".";
        String body = PersistentGraphSchemaWikiCompiler.withSummary(structuralBody, summary);
        if (existing == null && identityResolver != null) {
            var resolution = identityResolver.resolve(
                    KnowledgeConceptType.GRAPH_SPACE, null, null,
                    KnowledgeNamespaceType.GRAPH, namespaceKey(changeSet), changeSet.graphId(),
                    new WikiIdentityResolver.Draft(
                            "Graph Space " + changeSet.graphId(), summary, body),
                    WikiIdentityResolver.RevisionMode.AUTHORITATIVE_SNAPSHOT).orElse(null);
            if (resolution != null) {
                existing = resolution.previous();
                conceptId = existing.concept().id();
                if (sourceHash.equals(existing.currentRevision().metadata().get("capabilitySourceHash"))) {
                    return existing.currentRevision().id();
                }
            }
        }
        String contentHash = KnowledgeIdentity.sha256(body);

        Instant now = Instant.now();
        long expectedVersion = existing == null ? 0 : existing.concept().version();
        long revisionNumber = expectedVersion + 1;
        String resourceUri = resourceUri(changeSet.graphId(), changeSet.schemaId());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("graphId", changeSet.graphId());
        metadata.put("schemaId", changeSet.schemaId());
        metadata.put("requestId", changeSet.requestId());
        metadata.put("resourceUri", resourceUri);
        metadata.put("entityTypes", new TreeSet<>(schema.nodeTypes().keySet()).stream().toList());
        metadata.put("relationTypes", new TreeSet<>(schema.relationTypes().keySet()).stream().toList());
        metadata.put("typicalQueries", PersistentGraphSchemaWikiCompiler.typicalQueries(schema));
        metadata.put("recommendedTool", "query_graph");
        metadata.put("capabilitySourceHash", sourceHash);
        if (existing != null) metadata.put("previousRevisionId", existing.currentRevision().id());

        KnowledgeRevision revision = new KnowledgeRevision(
                KnowledgeIdentity.revisionId(conceptId, revisionNumber, contentHash),
                conceptId, revisionNumber, "Graph Space " + changeSet.graphId(),
                summary,
                body, COMPILER_ID, now, contentHash, Map.copyOf(metadata), now);
        KnowledgeConcept concept = new KnowledgeConcept(
                conceptId, null, null, KnowledgeNamespaceType.GRAPH,
                namespaceKey(changeSet), KnowledgeConceptType.GRAPH_SPACE,
                changeSet.graphId(), KnowledgeStatus.STABLE,
                revision.id(), revisionNumber, null,
                existing == null ? now : existing.concept().createdAt(), now);
        KnowledgeSource source = new KnowledgeSource(
                revision.id(), KnowledgeSourceType.BUSINESS_RESULT,
                changeSet.requestId(), resourceUri, now, now);
        KnowledgeIndexTask indexTask = new KnowledgeIndexTask(
                null, concept.id(), revision.id(), KnowledgeIndexOperation.UPSERT_CURRENT,
                KnowledgeIndexTaskStatus.PENDING, 0, now, null, null, null, now);
        repository.commitChanges(List.of(new KnowledgeRevisionChange(
                concept, expectedVersion, revision, List.of(source),
                List.of(), List.of(), List.of(indexTask))));
        return revision.id();
    }

    @Override
    public void deprecate(String graphId, String schemaId) {
        String conceptId = conceptId(graphId, schemaId);
        var existing = repository.findById(conceptId);
        if (existing.isEmpty()
                || existing.get().concept().status() == KnowledgeStatus.DEPRECATED) {
            return;
        }

        KnowledgeHead head = existing.get();
        Instant now = Instant.now();
        long revisionNumber = head.concept().version() + 1;
        String body = head.currentRevision().body() + "\n\nStatus: deprecated\n";
        String contentHash = KnowledgeIdentity.sha256(body);
        Map<String, Object> metadata = new LinkedHashMap<>(head.currentRevision().metadata());
        metadata.put("enabled", false);
        metadata.put("deprecatedAt", now.toString());
        metadata.put("previousRevisionId", head.currentRevision().id());
        KnowledgeRevision revision = new KnowledgeRevision(
                KnowledgeIdentity.revisionId(conceptId, revisionNumber, contentHash),
                conceptId, revisionNumber, head.currentRevision().title(),
                head.currentRevision().description(), body, COMPILER_ID, now,
                contentHash, metadata, now);
        KnowledgeConcept concept = new KnowledgeConcept(
                conceptId, head.concept().tenantId(), null, KnowledgeNamespaceType.GRAPH,
                namespaceKey(graphId, schemaId), KnowledgeConceptType.GRAPH_SPACE,
                required(graphId, "graphId"), KnowledgeStatus.DEPRECATED, revision.id(),
                revisionNumber, null, head.concept().createdAt(), now);
        KnowledgeSource source = new KnowledgeSource(
                revision.id(), KnowledgeSourceType.KNOWLEDGE_CONCEPT,
                head.currentRevision().id(), resourceUri(graphId, schemaId),
                head.currentRevision().generatedAt(), now);
        KnowledgeIndexTask indexTask = new KnowledgeIndexTask(
                null, conceptId, revision.id(), KnowledgeIndexOperation.DELETE_CONCEPT,
                KnowledgeIndexTaskStatus.PENDING, 0, now, null, null, null, now);
        repository.commitChanges(List.of(new KnowledgeRevisionChange(
                concept, head.concept().version(), revision, List.of(source),
                List.of(), List.of(), List.of(indexTask))));
    }

    private static String renderBody(GraphChangeSet changeSet, GraphSchemaDefinition schema) {
        StringBuilder body = new StringBuilder("# Graph Space " + changeSet.graphId() + "\n\n")
                .append("- Schema: ").append(changeSet.schemaId()).append("\n\n");
        // A committed mutation means the Schema was registered and readable, so it is enabled.
        PersistentGraphSchemaWikiCompiler.appendSchemaDefinition(body, schema, true);
        return body.toString();
    }

    private static String conceptId(String graphId, String schemaId) {
        return KnowledgeIdentity.wikiConceptId(
                null, KnowledgeConceptType.GRAPH_SPACE,
                namespaceKey(graphId, schemaId), required(graphId, "graphId"));
    }

    private static String resourceUri(String graphId, String schemaId) {
        return "cyrene://graphs/" + graphId + "?schemaId=" + schemaId;
    }

    private static String namespaceKey(GraphChangeSet changeSet) {
        return namespaceKey(changeSet.graphId(), changeSet.schemaId());
    }

    private static String namespaceKey(String graphId, String schemaId) {
        return required(graphId, "graphId") + ':' + required(schemaId, "schemaId");
    }

    private static String required(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }
}

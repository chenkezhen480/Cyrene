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

/** Writes one small Catalog entry per graphId/schemaId; graph rows remain only in Neo4j. */
public final class PersistentGraphSpaceWikiCompiler implements GraphSpaceWikiCompiler {

    private static final String COMPILER_ID = "cyrene-graph-space-wiki-compiler/v2";

    private final KnowledgeRepository repository;
    private final GraphSchemaRegistry schemaRegistry;
    private final GraphCapabilityDescriber capabilityDescriber;
    private final WikiIdentityResolver identityResolver;

    public PersistentGraphSpaceWikiCompiler(KnowledgeRepository repository, GraphSchemaRegistry schemaRegistry,
                                            GraphCapabilityDescriber capabilityDescriber) {
        this(repository, schemaRegistry, capabilityDescriber, null);
    }

    public PersistentGraphSpaceWikiCompiler(
            KnowledgeRepository repository,
            GraphSchemaRegistry schemaRegistry,
            GraphCapabilityDescriber capabilityDescriber,
            WikiIdentityResolver identityResolver
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.schemaRegistry = Objects.requireNonNull(schemaRegistry, "schemaRegistry");
        this.capabilityDescriber = Objects.requireNonNull(capabilityDescriber, "capabilityDescriber");
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

        String conceptId = KnowledgeIdentity.wikiConceptId(
                null, KnowledgeConceptType.GRAPH_SPACE,
                namespaceKey(changeSet), changeSet.graphId());
        KnowledgeHead existing = repository.findById(conceptId).orElse(null);
        GraphSchemaDefinition schema = schemaRegistry.require(changeSet.schemaId());
        String body = renderBody(changeSet, schema);
        String sourceHash = KnowledgeIdentity.sha256(body);
        if (existing != null && existing.concept().status() == KnowledgeStatus.STABLE
                && sourceHash.equals(existing.currentRevision().metadata().get("capabilitySourceHash"))) {
            return existing.currentRevision().id();
        }
        String summary = capabilityDescriber.describe(schema);
        body += "\n\n## AI capability description\n" + summary + '\n';
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

    private static String renderBody(GraphChangeSet changeSet, GraphSchemaDefinition schema) {
        StringBuilder body = new StringBuilder("# Graph Space " + changeSet.graphId() + "\n\n"
                + "- Schema: " + changeSet.schemaId() + '\n'
                + "- Resource: " + resourceUri(
                changeSet.graphId(), changeSet.schemaId()) + '\n'
                + "- Entity types: " + String.join(", ", new TreeSet<>(schema.nodeTypes().keySet())) + '\n'
                + "- Relation types: " + String.join(", ", new TreeSet<>(schema.relationTypes().keySet())) + '\n'
                + "- Recommended tool: query_graph\n");
        PersistentGraphSchemaWikiCompiler.appendSchemaDefinition(body, schema);
        return body.append("\nThis capability card contains no entity or relationship facts. Use query_graph to read Neo4j.").toString();
    }

    private static String resourceUri(String graphId, String schemaId) {
        return "cyrene://graphs/" + graphId + "?schemaId=" + schemaId;
    }

    private static String namespaceKey(GraphChangeSet changeSet) {
        return changeSet.graphId() + ':' + changeSet.schemaId();
    }
}

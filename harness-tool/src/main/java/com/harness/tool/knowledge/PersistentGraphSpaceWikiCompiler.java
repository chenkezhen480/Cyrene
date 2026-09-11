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

    private static final String COMPILER_ID = "cyrene-graph-space-wiki-compiler/v1";

    private final KnowledgeRepository repository;

    public PersistentGraphSpaceWikiCompiler(KnowledgeRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
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
        if (existing != null && existing.concept().status() == KnowledgeStatus.STABLE) {
            return existing.currentRevision().id();
        }

        Instant now = Instant.now();
        long expectedVersion = existing == null ? 0 : existing.concept().version();
        long revisionNumber = expectedVersion + 1;
        String body = renderBody(changeSet);
        String contentHash = KnowledgeIdentity.sha256(body);
        String resourceUri = resourceUri(changeSet.graphId(), changeSet.schemaId());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("graphId", changeSet.graphId());
        metadata.put("schemaId", changeSet.schemaId());
        metadata.put("requestId", changeSet.requestId());
        metadata.put("resourceUri", resourceUri);
        metadata.put("nodeTypes", nodeTypes(changeSet));
        metadata.put("relationTypes", relationTypes(changeSet));

        KnowledgeRevision revision = new KnowledgeRevision(
                KnowledgeIdentity.revisionId(conceptId, revisionNumber, contentHash),
                conceptId, revisionNumber, "Graph Space " + changeSet.graphId(),
                "Searchable entry for a Neo4j graph space governed by Schema "
                        + changeSet.schemaId() + '.',
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

    private static String renderBody(GraphChangeSet changeSet) {
        return "# Graph Space " + changeSet.graphId() + "\n\n"
                + "- Schema: " + changeSet.schemaId() + '\n'
                + "- Resource: " + resourceUri(
                changeSet.graphId(), changeSet.schemaId()) + '\n'
                + "- Node types: " + display(nodeTypes(changeSet)) + '\n'
                + "- Relation types: " + display(relationTypes(changeSet)) + '\n'
                + "\nUse the graph route to find nodes and expand their relationships.";
    }

    private static List<String> nodeTypes(GraphChangeSet changeSet) {
        TreeSet<String> types = new TreeSet<>();
        changeSet.nodes().forEach(node -> types.addAll(node.labels()));
        return List.copyOf(types);
    }

    private static List<String> relationTypes(GraphChangeSet changeSet) {
        TreeSet<String> types = new TreeSet<>();
        changeSet.relations().forEach(relation -> types.add(relation.relationType()));
        return List.copyOf(types);
    }

    private static String display(List<String> values) {
        return values.isEmpty() ? "not specified by the initial mutation" : String.join(", ", values);
    }

    private static String resourceUri(String graphId, String schemaId) {
        return "cyrene://graphs/" + graphId + "?schemaId=" + schemaId;
    }

    private static String namespaceKey(GraphChangeSet changeSet) {
        return changeSet.graphId() + ':' + changeSet.schemaId();
    }
}

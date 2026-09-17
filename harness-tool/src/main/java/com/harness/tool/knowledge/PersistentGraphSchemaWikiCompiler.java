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
import com.harness.graph.schema.GraphPropertyDefinition;
import com.harness.graph.schema.GraphSchemaDefinition;
import com.harness.graph.schema.GraphSchemaDetails;
import com.harness.tool.knowledge.authority.KnowledgeHead;
import com.harness.tool.knowledge.authority.KnowledgeRepository;
import com.harness.tool.knowledge.authority.KnowledgeRevisionChange;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Transactionally writes Graph Schema Wiki revisions and their Catalog outbox tasks. */
public final class PersistentGraphSchemaWikiCompiler implements GraphSchemaWikiCompiler {

    private static final String COMPILER_ID = "cyrene-graph-schema-wiki-compiler/v2";

    private final KnowledgeRepository repository;
    private final GraphCapabilityDescriber capabilityDescriber;
    private final WikiIdentityResolver identityResolver;

    public PersistentGraphSchemaWikiCompiler(KnowledgeRepository repository, GraphCapabilityDescriber capabilityDescriber) {
        this(repository, capabilityDescriber, null);
    }

    public PersistentGraphSchemaWikiCompiler(
            KnowledgeRepository repository,
            GraphCapabilityDescriber capabilityDescriber,
            WikiIdentityResolver identityResolver
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.capabilityDescriber = Objects.requireNonNull(capabilityDescriber, "capabilityDescriber");
        this.identityResolver = identityResolver;
    }

    @Override
    public void synchronize(GraphSchemaDetails schema) {
        Objects.requireNonNull(schema, "schema");
        GraphSchemaDefinition definition = Objects.requireNonNull(
                schema.definition(), "schema.definition");
        String schemaId = definition.schemaId();
        String conceptId = conceptId(schemaId);
        KnowledgeHead existing = repository.findById(conceptId).orElse(null);
        Instant now = Instant.now();
        long expectedVersion = existing == null ? 0 : existing.concept().version();
        long revisionNumber = expectedVersion + 1;
        String structuralBody = renderBody(schema);
        String sourceHash = KnowledgeIdentity.sha256(structuralBody);
        if (existing != null
                && existing.concept().status() == KnowledgeStatus.STABLE
                && sourceHash.equals(existing.currentRevision().metadata().get("capabilitySourceHash"))) {
            return;
        }
        String summary = capabilityDescriber.describe(definition);
        // The description leads the card, where it reads as the one-line summary of the graph. It is
        // added after the hash check on the structural part so an unchanged Schema still skips the
        // model call entirely.
        String body = withSummary(structuralBody, summary);
        if (existing == null && identityResolver != null) {
            var resolution = identityResolver.resolve(
                    KnowledgeConceptType.GRAPH_SCHEMA, null, null,
                    KnowledgeNamespaceType.GRAPH, schemaId, schemaId,
                    new WikiIdentityResolver.Draft(
                            "Graph Schema " + schemaId, summary, body),
                    WikiIdentityResolver.RevisionMode.AUTHORITATIVE_SNAPSHOT).orElse(null);
            if (resolution != null) {
                existing = resolution.previous();
                conceptId = existing.concept().id();
                expectedVersion = existing.concept().version();
                revisionNumber = expectedVersion + 1;
                if (sourceHash.equals(existing.currentRevision().metadata().get("capabilitySourceHash"))) {
                    return;
                }
            }
        }
        String contentHash = KnowledgeIdentity.sha256(body);
        Map<String, Object> metadata = new LinkedHashMap<>(metadata(schema));
        metadata.put("capabilitySourceHash", sourceHash);
        if (existing != null) metadata.put("previousRevisionId", existing.currentRevision().id());
        KnowledgeRevision revision = new KnowledgeRevision(
                KnowledgeIdentity.revisionId(conceptId, revisionNumber, contentHash),
                conceptId, revisionNumber, "Graph Schema " + schemaId,
                summary, body, COMPILER_ID, now, contentHash, metadata, now);
        KnowledgeConcept concept = new KnowledgeConcept(
                conceptId, null, null, KnowledgeNamespaceType.GRAPH, schemaId,
                KnowledgeConceptType.GRAPH_SCHEMA, schemaId, KnowledgeStatus.STABLE,
                revision.id(), revisionNumber, null,
                existing == null ? now : existing.concept().createdAt(), now);
        KnowledgeSource source = new KnowledgeSource(
                revision.id(), KnowledgeSourceType.BUSINESS_RESULT, schemaId,
                resourceUri(schemaId), now, now);
        repository.commitChanges(List.of(new KnowledgeRevisionChange(
                concept, expectedVersion, revision, List.of(source), List.of(), List.of(),
                List.of(indexTask(conceptId, revision.id(),
                        KnowledgeIndexOperation.UPSERT_CURRENT, now)))));
    }

    @Override
    public void deprecate(String schemaId) {
        String normalizedSchemaId = required(schemaId);
        String conceptId = conceptId(normalizedSchemaId);
        Optional<KnowledgeHead> existing = repository.findById(conceptId);
        if (existing.isEmpty() || existing.get().concept().status() == KnowledgeStatus.DEPRECATED) {
            return;
        }

        KnowledgeHead head = existing.get();
        Instant now = Instant.now();
        long revisionNumber = head.concept().version() + 1;
        String body = head.currentRevision().body()
                + "\n\nStatus: deprecated\n";
        String contentHash = KnowledgeIdentity.sha256(body);
        Map<String, Object> metadata = new LinkedHashMap<>(
                head.currentRevision().metadata());
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
                normalizedSchemaId, KnowledgeConceptType.GRAPH_SCHEMA,
                normalizedSchemaId, KnowledgeStatus.DEPRECATED, revision.id(),
                revisionNumber, null, head.concept().createdAt(), now);
        KnowledgeSource source = new KnowledgeSource(
                revision.id(), KnowledgeSourceType.KNOWLEDGE_CONCEPT,
                head.currentRevision().id(), resourceUri(normalizedSchemaId),
                head.currentRevision().generatedAt(), now);
        repository.commitChanges(List.of(new KnowledgeRevisionChange(
                concept, head.concept().version(), revision, List.of(source),
                List.of(), List.of(), List.of(indexTask(
                        conceptId, revision.id(), KnowledgeIndexOperation.DELETE_CONCEPT, now)))));
    }

    private KnowledgeIndexTask indexTask(
            String conceptId,
            String revisionId,
            KnowledgeIndexOperation operation,
            Instant now
    ) {
        return new KnowledgeIndexTask(
                null, conceptId, revisionId, operation,
                KnowledgeIndexTaskStatus.PENDING, 0, now, null, null, null, now);
    }

    private static String renderBody(GraphSchemaDetails schema) {
        GraphSchemaDefinition definition = schema.definition();
        StringBuilder body = new StringBuilder("# Graph Schema ").append(definition.schemaId())
                .append("\n\n");
        appendSchemaDefinition(body, definition, schema.enabled());
        return body.toString();
    }

    /** Places the model-written one-liner directly under the title. */
    static String withSummary(String structuralBody, String summary) {
        int head = structuralBody.indexOf("\n\n");
        if (head < 0 || summary == null || summary.isBlank()) {
            return structuralBody;
        }
        return structuralBody.substring(0, head + 2) + summary + "\n\n"
                + structuralBody.substring(head + 2);
    }

    /**
     * Renders the whole card as structure, queryable boundary, and the tool to call.
     *
     * <p>Deliberately compact: this card exists so the model decides whether to call query_graph and
     * with which labels, not so it can read the graph. Restating a relation in prose after listing it
     * as an edge, or repeating the Schema's own version, mode and storage names, adds tokens without
     * adding a decision. Concrete nodes and relations are always read from Neo4j.</p>
     *
     * <p>A disabled Schema still gets a card, because its owner needs to see what it can answer
     * before enabling it, but the card says so: {@code query_graph} cannot read it, and
     * {@code KnowledgeDiscoveryRouter} leaves disabled cards out of the Agent's capability hints.</p>
     */
    static void appendSchemaDefinition(
            StringBuilder body,
            GraphSchemaDefinition definition,
            boolean enabled
    ) {
        body.append("Nodes:\n");
        new TreeMap<>(definition.nodeTypes()).forEach((label, nodeType) -> {
            body.append("- ").append(label);
            appendPropertyNames(body, nodeType.properties());
            body.append('\n');
        });
        body.append("\nRelations:\n");
        if (definition.relationTypes().isEmpty()) {
            body.append("- None\n");
        } else {
            new TreeMap<>(definition.relationTypes()).forEach((type, relation) ->
                    body.append("- ").append(String.join(", ", new TreeSet<>(relation.sourceLabels())))
                            .append(" -[").append(type).append("]-> ")
                            .append(String.join(", ", new TreeSet<>(relation.targetLabels())))
                            .append('\n'));
        }
        body.append("\nSupported queries:\n");
        typicalQueries(definition).forEach(query -> body.append("- ").append(query).append('\n'));
        body.append("\nTraversal: default depth ").append(definition.defaultMaxDepth())
                .append(", max depth ").append(definition.maxDepth());
        if (!hasMultiHopPath(definition)) {
            body.append("; no relation leads into another, so every query stays within one hop");
        }
        body.append('.');
        if (!enabled) {
            body.append("\n\nThis Schema is not enabled, so query_graph cannot read it yet.");
        }
        body.append("\n\nUse query_graph to read actual graph data. This card describes structure only;"
                + " entities and relations live in Neo4j.\n");
    }

    static List<String> typicalQueries(GraphSchemaDefinition definition) {
        java.util.ArrayList<String> queries = new java.util.ArrayList<>();
        new TreeMap<>(definition.nodeTypes()).forEach((label, type) ->
                queries.add("Find " + label + " entities by name."));
        new TreeMap<>(definition.relationTypes()).forEach((type, relation) ->
                queries.add("Which " + String.join(", ", new java.util.TreeSet<>(relation.targetLabels()))
                        + " entities are connected to " + String.join(", ", new java.util.TreeSet<>(relation.sourceLabels()))
                        + " by " + type + "?"));
        // Only claim path traversal when a relation's target type is also a relation's source type.
        if (hasMultiHopPath(definition)) {
            queries.add("What relationships and bounded paths connect these entities?");
        }
        return List.copyOf(queries);
    }

    /** True when some relation can be followed by another, i.e. a two-hop path can exist. */
    static boolean hasMultiHopPath(GraphSchemaDefinition definition) {
        Set<String> targets = definition.relationTypes().values().stream()
                .flatMap(relation -> relation.targetLabels().stream())
                .collect(java.util.stream.Collectors.toSet());
        return definition.relationTypes().values().stream()
                .anyMatch(relation -> relation.sourceLabels().stream().anyMatch(targets::contains));
    }

    private static void appendPropertyNames(
            StringBuilder body,
            Map<String, GraphPropertyDefinition> properties
    ) {
        if (properties.isEmpty()) {
            return;
        }
        body.append('(').append(String.join(", ", new TreeSet<>(properties.keySet()))).append(')');
    }

    private static Map<String, Object> metadata(GraphSchemaDetails schema) {
        GraphSchemaDefinition definition = schema.definition();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemaId", definition.schemaId());
        metadata.put("schemaVersion", definition.version());
        metadata.put("mode", definition.mode().name());
        metadata.put("enabled", schema.enabled());
        metadata.put("source", schema.source().name());
        metadata.put("format", schema.format().name());
        metadata.put("editable", schema.editable());
        metadata.put("resourceUri", resourceUri(definition.schemaId()));
        metadata.put("nodeTypeCount", definition.nodeTypes().size());
        metadata.put("relationTypeCount", definition.relationTypes().size());
        metadata.put("entityTypes", new java.util.TreeSet<>(definition.nodeTypes().keySet()).stream().toList());
        metadata.put("relationTypes", new java.util.TreeSet<>(definition.relationTypes().keySet()).stream().toList());
        metadata.put("typicalQueries", typicalQueries(definition));
        metadata.put("recommendedTool", "query_graph");
        return Map.copyOf(metadata);
    }

    private static String conceptId(String schemaId) {
        String normalizedSchemaId = required(schemaId);
        return KnowledgeIdentity.wikiConceptId(
                null, KnowledgeConceptType.GRAPH_SCHEMA,
                normalizedSchemaId, normalizedSchemaId);
    }

    private static String resourceUri(String schemaId) {
        return "cyrene://graph-schemas/" + required(schemaId);
    }

    private static String required(String schemaId) {
        if (schemaId == null || schemaId.isBlank()) {
            throw new IllegalArgumentException("schemaId is required");
        }
        return schemaId.trim();
    }
}

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
import com.harness.graph.schema.GraphNodeTypeDefinition;
import com.harness.graph.schema.GraphPropertyDefinition;
import com.harness.graph.schema.GraphRelationTypeDefinition;
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
import java.util.TreeMap;

/** Transactionally writes Graph Schema Wiki revisions and their Catalog outbox tasks. */
public final class PersistentGraphSchemaWikiCompiler implements GraphSchemaWikiCompiler {

    private static final String COMPILER_ID = "cyrene-graph-schema-wiki-compiler/v2";

    private final KnowledgeRepository repository;
    private final GraphCapabilityDescriber capabilityDescriber;

    public PersistentGraphSchemaWikiCompiler(KnowledgeRepository repository, GraphCapabilityDescriber capabilityDescriber) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.capabilityDescriber = Objects.requireNonNull(capabilityDescriber, "capabilityDescriber");
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
        String body = renderBody(schema);
        String sourceHash = KnowledgeIdentity.sha256(body);
        if (existing != null
                && existing.concept().status() == KnowledgeStatus.STABLE
                && sourceHash.equals(existing.currentRevision().metadata().get("capabilitySourceHash"))) {
            return;
        }
        String summary = capabilityDescriber.describe(definition);
        body += "\n## AI capability description\n" + summary + '\n';
        String contentHash = KnowledgeIdentity.sha256(body);
        Map<String, Object> metadata = new LinkedHashMap<>(metadata(schema));
        metadata.put("capabilitySourceHash", sourceHash);
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
        StringBuilder body = new StringBuilder()
                .append("# Graph Schema ").append(definition.schemaId()).append("\n\n")
                .append("- Version: ").append(definition.version()).append('\n')
                .append("- Mode: ").append(definition.mode()).append('\n')
                .append("- Enabled: ").append(schema.enabled()).append('\n')
                .append("- Source: ").append(schema.source()).append('\n')
                .append("- Format: ").append(schema.format()).append('\n')
                .append("- Editable: ").append(schema.editable()).append('\n')
                .append("- Resource: ").append(resourceUri(definition.schemaId())).append('\n');
        appendSchemaDefinition(body, definition);
        body.append("\nRecommended tool: query_graph\n")
                .append("This card describes graph capabilities only. Entity and relationship facts stay in Neo4j.\n");
        return body.toString();
    }

    static void appendSchemaDefinition(StringBuilder body, GraphSchemaDefinition definition) {
        body.append("- Schema version: ").append(definition.version()).append('\n')
                .append("- Schema mode: ").append(definition.mode()).append('\n')
                .append("- Default max depth: ").append(definition.defaultMaxDepth()).append('\n')
                .append("- Max depth: ").append(definition.maxDepth())
                .append("\n\n## Node types\n");
        new TreeMap<>(definition.nodeTypes()).forEach((label, nodeType) ->
                appendNodeType(body, label, nodeType));
        body.append("\n## Relation types\n");
        if (definition.relationTypes().isEmpty()) {
            body.append("- None\n");
        } else {
            new TreeMap<>(definition.relationTypes()).forEach((type, relationType) ->
                    appendRelationType(body, type, relationType));
        }
        body.append("\n## Typical queries\n");
        typicalQueries(definition).forEach(query -> body.append("- ").append(query).append('\n'));
    }

    static List<String> typicalQueries(GraphSchemaDefinition definition) {
        java.util.ArrayList<String> queries = new java.util.ArrayList<>();
        new TreeMap<>(definition.nodeTypes()).forEach((label, type) ->
                queries.add("Find " + label + " entities by name."));
        new TreeMap<>(definition.relationTypes()).forEach((type, relation) ->
                queries.add("Which " + String.join(", ", new java.util.TreeSet<>(relation.targetLabels()))
                        + " entities are connected to " + String.join(", ", new java.util.TreeSet<>(relation.sourceLabels()))
                        + " by " + type + "?"));
        if (!definition.relationTypes().isEmpty()) {
            queries.add("What relationships and bounded paths connect these entities?");
        }
        return List.copyOf(queries);
    }

    private static void appendNodeType(
            StringBuilder body,
            String label,
            GraphNodeTypeDefinition nodeType
    ) {
        body.append("- ").append(label);
        appendProperties(body, nodeType.properties());
        body.append('\n');
    }

    private static void appendRelationType(
            StringBuilder body,
            String type,
            GraphRelationTypeDefinition relationType
    ) {
        body.append("- ").append(type)
                .append(": ").append(String.join(", ", new java.util.TreeSet<>(
                        relationType.sourceLabels())))
                .append(" -> ").append(String.join(", ", new java.util.TreeSet<>(
                        relationType.targetLabels())));
        appendProperties(body, relationType.properties());
        body.append('\n');
    }

    private static void appendProperties(
            StringBuilder body,
            Map<String, GraphPropertyDefinition> properties
    ) {
        if (properties.isEmpty()) {
            return;
        }
        body.append(" [");
        boolean first = true;
        for (Map.Entry<String, GraphPropertyDefinition> entry
                : new TreeMap<>(properties).entrySet()) {
            if (!first) {
                body.append(", ");
            }
            GraphPropertyDefinition property = entry.getValue();
            body.append(entry.getKey()).append(':').append(property.type());
            if (property.required()) body.append(" required");
            if (property.sensitive()) body.append(" sensitive");
            if (property.queryable()) body.append(" queryable");
            if (property.sortable()) body.append(" sortable");
            first = false;
        }
        body.append(']');
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

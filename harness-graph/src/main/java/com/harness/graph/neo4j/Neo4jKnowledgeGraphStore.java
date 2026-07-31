package com.harness.graph.neo4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.PageResponse;
import com.harness.graph.config.GraphSettings;
import com.harness.graph.model.GraphDeleteMode;
import com.harness.graph.model.GraphDeleteRequest;
import com.harness.graph.model.GraphDeleteResult;
import com.harness.graph.model.GraphDeleteTarget;
import com.harness.graph.model.GraphMutationBatch;
import com.harness.graph.model.GraphMutationResult;
import com.harness.graph.model.GraphNeighborhoodRequest;
import com.harness.graph.model.GraphNode;
import com.harness.graph.model.GraphNodeKey;
import com.harness.graph.model.GraphNodePageRequest;
import com.harness.graph.model.GraphRelation;
import com.harness.graph.model.GraphRelationPageRequest;
import com.harness.graph.model.GraphRouteResult;
import com.harness.graph.model.GraphSpacePageRequest;
import com.harness.graph.model.GraphSpaceSummary;
import com.harness.graph.schema.GraphRelationTypeDefinition;
import com.harness.graph.schema.GraphSchemaDefinition;
import com.harness.graph.schema.GraphSchemaRegistry;
import com.harness.graph.schema.GraphSchemaValidator;
import com.harness.graph.store.GraphStoreException;
import com.harness.graph.store.KnowledgeGraphStore;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionConfig;
import org.neo4j.driver.exceptions.Neo4jException;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.neo4j.driver.Values.parameters;

public final class Neo4jKnowledgeGraphStore implements KnowledgeGraphStore {

    private static final Logger log = LoggerFactory.getLogger(Neo4jKnowledgeGraphStore.class);
    private static final String BASE_NODE_LABEL = "HarnessGraphNode";
    private static final String MUTATION_LABEL = "HarnessGraphMutation";

    private final Driver driver;
    private final GraphSettings settings;
    private final GraphSchemaRegistry schemaRegistry;
    private final GraphSchemaValidator schemaValidator;
    private final Neo4jValueMapper valueMapper;
    private final SessionConfig readSessionConfig;
    private final SessionConfig writeSessionConfig;
    private final TransactionConfig queryTransactionConfig;

    public Neo4jKnowledgeGraphStore(
            Driver driver,
            GraphSettings settings,
            GraphSchemaRegistry schemaRegistry,
            ObjectMapper objectMapper
    ) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.schemaRegistry = Objects.requireNonNull(schemaRegistry, "schemaRegistry");
        this.schemaValidator = new GraphSchemaValidator(schemaRegistry);
        this.valueMapper = new Neo4jValueMapper(Objects.requireNonNull(objectMapper, "objectMapper"));
        this.readSessionConfig = SessionConfig.builder()
                .withDatabase(settings.neo4jDatabase())
                .withDefaultAccessMode(AccessMode.READ)
                .build();
        this.writeSessionConfig = SessionConfig.builder()
                .withDatabase(settings.neo4jDatabase())
                .withDefaultAccessMode(AccessMode.WRITE)
                .build();
        this.queryTransactionConfig = TransactionConfig.builder()
                .withTimeout(settings.queryTimeout())
                .build();
        initializeConstraints();
    }

    @Override
    public GraphMutationResult upsertBatch(GraphMutationBatch mutationBatch) {
        schemaValidator.validate(mutationBatch);
        GraphSchemaDefinition schema = schemaRegistry.require(mutationBatch.schemaId());

        try (Session session = driver.session(writeSessionConfig)) {
            return session.executeWrite(transaction -> {
                GraphMutationResult existing = findMutationResult(transaction, mutationBatch);
                if (existing != null) {
                    return existing;
                }

                int nodeCount = upsertNodes(transaction, mutationBatch, schema);
                int relationCount = upsertRelations(transaction, mutationBatch, schema);
                if (nodeCount != mutationBatch.nodes().size()) {
                    throw new GraphStoreException("Neo4j node batch count mismatch: expected "
                            + mutationBatch.nodes().size() + " but got " + nodeCount);
                }
                if (relationCount != mutationBatch.relations().size()) {
                    throw new GraphStoreException("Neo4j relation batch count mismatch: expected "
                            + mutationBatch.relations().size() + " but got " + relationCount);
                }

                GraphMutationResult result = new GraphMutationResult(
                        mutationBatch.requestId(), true, nodeCount, relationCount);
                recordMutation(transaction, mutationBatch, result);
                return result;
            }, queryTransactionConfig);
        } catch (Neo4jException | GraphStoreException e) {
            throw new GraphStoreException("Knowledge graph mutation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public GraphNode getNode(GraphNodeKey nodeKey) {
        schemaRegistry.require(nodeKey.schemaId());
        String cypher = """
                MATCH (n:HarnessGraphNode {storageKey: $storageKey})
                RETURN n
                """;
        try (Session session = driver.session(readSessionConfig)) {
            return session.executeRead(transaction -> {
                var result = transaction.run(cypher, parameters("storageKey", nodeStorageKey(
                        nodeKey.graphId(), nodeKey.schemaId(), nodeKey.nodeId())));
                return result.hasNext() ? toGraphNode(result.single().get("n").asNode()) : null;
            }, queryTransactionConfig);
        } catch (Neo4jException e) {
            throw new GraphStoreException("Failed to get graph node: " + e.getMessage(), e);
        }
    }

    @Override
    public PageResponse<GraphNode> listNodes(GraphNodePageRequest request) {
        schemaValidator.validateNodeLabel(request.schemaId(), request.label());
        int limit = settings.capLimit(request.limit());
        String labelClause = request.label().isBlank() ? "" : ":" + request.label();
        String cypher = """
                MATCH (n:HarnessGraphNode%s)
                WHERE n.graphId = $graphId
                  AND n.schemaId = $schemaId
                  AND ($name = '' OR toLower(toString(n.name)) CONTAINS toLower($name))
                  AND ($cursor = '' OR n.nodeId > $cursor)
                RETURN n
                ORDER BY n.nodeId
                LIMIT $fetchLimit
                """.formatted(labelClause);

        try (Session session = driver.session(readSessionConfig)) {
            List<GraphNode> fetched = session.executeRead(transaction ->
                    transaction.run(cypher, parameters(
                                    "graphId", request.graphId(),
                                    "schemaId", request.schemaId(),
                                    "name", request.name(),
                                    "cursor", request.cursor(),
                                    "fetchLimit", limit + 1))
                            .list(record -> toGraphNode(record.get("n").asNode())), queryTransactionConfig);
            return page(fetched, limit, GraphNode::nodeId);
        } catch (Neo4jException e) {
            throw new GraphStoreException("Failed to list graph nodes: " + e.getMessage(), e);
        }
    }

    @Override
    public PageResponse<GraphRelation> listRelations(GraphRelationPageRequest request) {
        schemaValidator.validateRelationType(request.schemaId(), request.relationType());
        int limit = settings.capLimit(request.limit());
        String relationTypeClause = request.relationType().isBlank()
                ? ""
                : ":" + cypherIdentifier(request.relationType());
        String cypher = """
                MATCH (source:HarnessGraphNode)-[relation%s]->(target:HarnessGraphNode)
                WHERE source.graphId = $graphId
                  AND source.schemaId = $schemaId
                  AND target.graphId = $graphId
                  AND target.schemaId = $schemaId
                  AND relation.graphId = $graphId
                  AND relation.schemaId = $schemaId
                  AND ($cursor = '' OR relation.relationId > $cursor)
                RETURN source, relation, target
                ORDER BY relation.relationId
                LIMIT $fetchLimit
                """.formatted(relationTypeClause);

        try (Session session = driver.session(readSessionConfig)) {
            List<GraphRelation> fetched = session.executeRead(transaction ->
                    transaction.run(cypher, parameters(
                                    "graphId", request.graphId(),
                                    "schemaId", request.schemaId(),
                                    "cursor", request.cursor(),
                                    "fetchLimit", limit + 1))
                            .list(this::toGraphRelation), queryTransactionConfig);
            return page(fetched, limit, GraphRelation::relationId);
        } catch (Neo4jException e) {
            throw new GraphStoreException("Failed to list graph relations: " + e.getMessage(), e);
        }
    }

    @Override
    public PageResponse<GraphSpaceSummary> listGraphSpaces(GraphSpacePageRequest request) {
        int limit = settings.capLimit(request.limit());
        GraphSpaceCursor cursor = decodeGraphSpaceCursor(request.cursor());
        String cypher = """
                MATCH (node:HarnessGraphNode)
                WITH node.graphId AS graphId, node.schemaId AS schemaId, count(node) AS nodeCount
                WHERE $cursorGraphId = ''
                   OR graphId > $cursorGraphId
                   OR (graphId = $cursorGraphId AND schemaId > $cursorSchemaId)
                ORDER BY graphId, schemaId
                LIMIT $fetchLimit
                OPTIONAL MATCH (source:HarnessGraphNode)-[relation]->(target:HarnessGraphNode)
                WHERE source.graphId = graphId
                  AND source.schemaId = schemaId
                  AND target.graphId = graphId
                  AND target.schemaId = schemaId
                  AND relation.graphId = graphId
                  AND relation.schemaId = schemaId
                RETURN graphId, schemaId, nodeCount, count(relation) AS relationCount
                ORDER BY graphId, schemaId
                """;

        try (Session session = driver.session(readSessionConfig)) {
            List<GraphSpaceSummary> fetched = session.executeRead(transaction ->
                    transaction.run(cypher, parameters(
                                    "cursorGraphId", cursor.graphId(),
                                    "cursorSchemaId", cursor.schemaId(),
                                    "fetchLimit", limit + 1))
                            .list(record -> new GraphSpaceSummary(
                                    record.get("graphId").asString(),
                                    record.get("schemaId").asString(),
                                    record.get("nodeCount").asLong(),
                                    record.get("relationCount").asLong()
                            )), queryTransactionConfig);
            return page(fetched, limit, summary ->
                    encodeGraphSpaceCursor(summary.graphId(), summary.schemaId()));
        } catch (Neo4jException e) {
            throw new GraphStoreException("Failed to list graph spaces: " + e.getMessage(), e);
        }
    }

    @Override
    public GraphRouteResult findNeighborhood(GraphNeighborhoodRequest request) {
        GraphSchemaDefinition schema = schemaRegistry.require(request.schemaId());
        int depth = Math.min(settings.capDepth(request.maxDepth()), schema.maxDepth());
        int limit = settings.capLimit(request.limit());
        request.relationTypes().forEach(schema::requireRelationType);

        List<String> subjectKeys = request.subjectIds().stream()
                .map(subjectId -> nodeStorageKey(request.graphId(), request.schemaId(), subjectId))
                .toList();

        try (Session session = driver.session(readSessionConfig)) {
            return session.executeRead(transaction -> findNeighborhood(
                    transaction, request, subjectKeys, depth, limit), queryTransactionConfig);
        } catch (Neo4jException e) {
            throw new GraphStoreException("Failed to query graph neighborhood: " + e.getMessage(), e);
        }
    }

    @Override
    public GraphDeleteResult delete(GraphDeleteRequest request) {
        schemaRegistry.require(request.schemaId());
        try (Session session = driver.session(writeSessionConfig)) {
            return session.executeWrite(transaction -> switch (request.target()) {
                case NODE -> deleteNode(transaction, request);
                case RELATION -> deleteRelation(transaction, request);
                case SOURCE -> deleteBySource(transaction, request);
            }, queryTransactionConfig);
        } catch (Neo4jException | GraphStoreException e) {
            throw new GraphStoreException("Knowledge graph delete failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String providerName() {
        return "neo4j";
    }

    @Override
    public void close() {
        driver.close();
    }

    private void initializeConstraints() {
        List<String> statements = new ArrayList<>(List.of(
                """
                CREATE CONSTRAINT harness_graph_node_key IF NOT EXISTS
                FOR (node:HarnessGraphNode) REQUIRE node.storageKey IS UNIQUE
                """,
                """
                CREATE INDEX harness_graph_node_page IF NOT EXISTS
                FOR (node:HarnessGraphNode) ON (node.graphId, node.schemaId, node.nodeId)
                """,
                """
                CREATE CONSTRAINT harness_graph_mutation_key IF NOT EXISTS
                FOR (mutation:HarnessGraphMutation) REQUIRE mutation.storageKey IS UNIQUE
                """
        ));
        schemaRegistry.list().forEach(schema -> schema.relationTypes().keySet().forEach(relationType -> {
            String relationTypeIdentifier = cypherIdentifier(relationType);
            statements.add("""
                        CREATE CONSTRAINT %s IF NOT EXISTS
                        FOR ()-[relation:%s]-() REQUIRE relation.storageKey IS UNIQUE
                        """.formatted(
                    relationConstraintName(schema.schemaId(), relationType),
                    relationTypeIdentifier));
            statements.add("""
                        CREATE INDEX %s IF NOT EXISTS
                        FOR ()-[relation:%s]-()
                        ON (relation.graphId, relation.schemaId, relation.relationId)
                        """.formatted(
                    relationPageIndexName(schema.schemaId(), relationType),
                    relationTypeIdentifier));
        }));
        try (Session session = driver.session(writeSessionConfig)) {
            session.executeWrite(transaction -> {
                statements.forEach(statement -> transaction.run(statement).consume());
                return null;
            }, queryTransactionConfig);
        } catch (Neo4jException e) {
            throw new GraphStoreException("Failed to initialize Neo4j constraints: " + e.getMessage(), e);
        }
    }

    private GraphMutationResult findMutationResult(
            org.neo4j.driver.TransactionContext transaction,
            GraphMutationBatch mutationBatch
    ) {
        String cypher = """
                MATCH (mutation:HarnessGraphMutation {storageKey: $storageKey})
                RETURN mutation.nodeCount AS nodeCount, mutation.relationCount AS relationCount
                """;
        var result = transaction.run(cypher, parameters(
                "storageKey", mutationStorageKey(mutationBatch)));
        if (!result.hasNext()) {
            return null;
        }
        Record record = result.single();
        return new GraphMutationResult(
                mutationBatch.requestId(),
                true,
                record.get("nodeCount").asInt(),
                record.get("relationCount").asInt()
        );
    }

    private int upsertNodes(
            org.neo4j.driver.TransactionContext transaction,
            GraphMutationBatch mutationBatch,
            GraphSchemaDefinition schema
    ) {
        int total = 0;
        Map<Set<String>, List<GraphNode>> groups = mutationBatch.nodes().stream()
                .collect(Collectors.groupingBy(GraphNode::labels, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<Set<String>, List<GraphNode>> group : groups.entrySet()) {
            String labels = group.getKey().stream()
                    .sorted()
                    .map(label -> ":" + label)
                    .collect(Collectors.joining());
            Set<String> managedProperties = group.getKey().stream()
                    .flatMap(label -> schema.requireNodeType(label).properties().keySet().stream())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            String clearManagedProperties = removePropertiesClause("node", managedProperties);
            String cypher = """
                    UNWIND $rows AS row
                    MERGE (node:HarnessGraphNode%s {storageKey: row.storageKey})
                    ON CREATE SET node.nodeId = row.nodeId,
                                  node.graphId = $graphId,
                                  node.schemaId = $schemaId,
                                  node.createdAt = datetime()
                    %s
                    SET node += row.properties,
                        node.updatedAt = datetime()
                    RETURN count(node) AS count
                    """.formatted(labels, clearManagedProperties);
            List<Map<String, Object>> rows = group.getValue().stream()
                    .map(node -> Map.<String, Object>of(
                            "storageKey", nodeStorageKey(
                                    mutationBatch.graphId(), mutationBatch.schemaId(), node.nodeId()),
                            "nodeId", node.nodeId(),
                            "properties", valueMapper.toStorageProperties(node.properties())))
                    .toList();
            total += transaction.run(cypher, parameters(
                    "rows", rows,
                    "graphId", mutationBatch.graphId(),
                    "schemaId", mutationBatch.schemaId())).single().get("count").asInt();
        }
        return total;
    }

    private int upsertRelations(
            org.neo4j.driver.TransactionContext transaction,
            GraphMutationBatch mutationBatch,
            GraphSchemaDefinition schema
    ) {
        int total = 0;
        Map<String, List<GraphRelation>> groups = mutationBatch.relations().stream()
                .collect(Collectors.groupingBy(
                        GraphRelation::relationType, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<String, List<GraphRelation>> group : groups.entrySet()) {
            GraphRelationTypeDefinition relationDefinition = schema.requireRelationType(group.getKey());
            String clearManagedProperties = removePropertiesClause(
                    "relation",
                    relationDefinition.properties().keySet());
            List<Map<String, Object>> rows = group.getValue().stream()
                    .map(relation -> Map.<String, Object>of(
                            "storageKey", relationStorageKey(
                                    mutationBatch.graphId(), mutationBatch.schemaId(), relation.relationId()),
                            "relationId", relation.relationId(),
                            "sourceStorageKey", nodeStorageKey(
                                    mutationBatch.graphId(), mutationBatch.schemaId(), relation.sourceNodeId()),
                            "targetStorageKey", nodeStorageKey(
                                    mutationBatch.graphId(), mutationBatch.schemaId(), relation.targetNodeId()),
                            "properties", valueMapper.toStorageProperties(relation.properties())))
                    .toList();
            var queryParameters = parameters(
                    "rows", rows,
                    "graphId", mutationBatch.graphId(),
                    "schemaId", mutationBatch.schemaId(),
                    "relationType", group.getKey(),
                    "sourceLabels", List.copyOf(relationDefinition.sourceLabels()),
                    "targetLabels", List.copyOf(relationDefinition.targetLabels()));

            deleteStructurallyChangedRelations(transaction, queryParameters);

            String upsertCypher = """
                    UNWIND $rows AS row
                    MATCH (source:HarnessGraphNode {storageKey: row.sourceStorageKey})
                    WHERE any(label IN labels(source) WHERE label IN $sourceLabels)
                    MATCH (target:HarnessGraphNode {storageKey: row.targetStorageKey})
                    WHERE any(label IN labels(target) WHERE label IN $targetLabels)
                    MERGE (source)-[relation:%s {storageKey: row.storageKey}]->(target)
                    ON CREATE SET relation.relationId = row.relationId,
                                  relation.graphId = $graphId,
                                  relation.schemaId = $schemaId,
                                  relation.createdAt = datetime()
                    %s
                    SET relation += row.properties,
                        relation.updatedAt = datetime()
                    RETURN count(relation) AS count
                    """.formatted(group.getKey(), clearManagedProperties);
            total += transaction.run(upsertCypher, queryParameters)
                    .single().get("count").asInt();
        }
        return total;
    }

    private static void deleteStructurallyChangedRelations(
            org.neo4j.driver.TransactionContext transaction,
            org.neo4j.driver.Value queryParameters
    ) {
        String cypher = """
                UNWIND $rows AS row
                MATCH (source:HarnessGraphNode {storageKey: row.sourceStorageKey})
                WHERE any(label IN labels(source) WHERE label IN $sourceLabels)
                MATCH (target:HarnessGraphNode {storageKey: row.targetStorageKey})
                WHERE any(label IN labels(target) WHERE label IN $targetLabels)
                OPTIONAL MATCH (existingSource:HarnessGraphNode)-[existing]->(existingTarget:HarnessGraphNode)
                WHERE existing.storageKey = row.storageKey
                  AND (type(existing) <> $relationType
                       OR existingSource <> source
                       OR existingTarget <> target)
                WITH existing
                WHERE existing IS NOT NULL
                DELETE existing
                """;
        transaction.run(cypher, queryParameters).consume();
    }

    private void recordMutation(
            org.neo4j.driver.TransactionContext transaction,
            GraphMutationBatch mutationBatch,
            GraphMutationResult result
    ) {
        String cypher = """
                CREATE (mutation:HarnessGraphMutation {
                    storageKey: $storageKey,
                    requestId: $requestId,
                    graphId: $graphId,
                    schemaId: $schemaId,
                    nodeCount: $nodeCount,
                    relationCount: $relationCount,
                    createdAt: datetime()
                })
                """;
        transaction.run(cypher, parameters(
                "storageKey", mutationStorageKey(mutationBatch),
                "requestId", result.requestId(),
                "graphId", mutationBatch.graphId(),
                "schemaId", mutationBatch.schemaId(),
                "nodeCount", result.nodeCount(),
                "relationCount", result.relationCount())).consume();
    }

    private GraphDeleteResult deleteNode(
            org.neo4j.driver.TransactionContext transaction,
            GraphDeleteRequest request
    ) {
        if (request.mode() == GraphDeleteMode.DELETE_DERIVED_ONLY) {
            throw new GraphStoreException("DELETE_DERIVED_ONLY is only valid for SOURCE deletion");
        }
        String storageKey = nodeStorageKey(request.graphId(), request.schemaId(), request.targetId());
        Record counts = transaction.run("""
                MATCH (node:HarnessGraphNode {storageKey: $storageKey})
                OPTIONAL MATCH (node)-[relation]-()
                RETURN count(DISTINCT node) AS nodeCount, count(DISTINCT relation) AS relationCount
                """, parameters("storageKey", storageKey)).single();
        int nodeCount = counts.get("nodeCount").asInt();
        int relationCount = counts.get("relationCount").asInt();
        if (nodeCount == 0) {
            return new GraphDeleteResult(0, 0);
        }
        if (request.mode() == GraphDeleteMode.REJECT_IF_REFERENCED && relationCount > 0) {
            throw new GraphStoreException("Graph node is still referenced by " + relationCount + " relations");
        }
        String deleteCypher = request.mode() == GraphDeleteMode.DETACH
                ? "MATCH (node:HarnessGraphNode {storageKey: $storageKey}) DETACH DELETE node"
                : "MATCH (node:HarnessGraphNode {storageKey: $storageKey}) DELETE node";
        transaction.run(deleteCypher, parameters("storageKey", storageKey)).consume();
        return new GraphDeleteResult(1, request.mode() == GraphDeleteMode.DETACH ? relationCount : 0);
    }

    private GraphDeleteResult deleteRelation(
            org.neo4j.driver.TransactionContext transaction,
            GraphDeleteRequest request
    ) {
        String cypher = """
                MATCH (source:HarnessGraphNode)-[relation]->(target:HarnessGraphNode)
                WHERE source.graphId = $graphId
                  AND source.schemaId = $schemaId
                  AND target.graphId = $graphId
                  AND target.schemaId = $schemaId
                  AND relation.storageKey = $storageKey
                WITH collect(relation) AS relations
                FOREACH (item IN relations | DELETE item)
                RETURN size(relations) AS deleted
                """;
        int deleted = transaction.run(cypher, parameters(
                "graphId", request.graphId(),
                "schemaId", request.schemaId(),
                "storageKey", relationStorageKey(
                        request.graphId(), request.schemaId(), request.targetId())))
                .single().get("deleted").asInt();
        return new GraphDeleteResult(0, deleted);
    }

    private GraphDeleteResult deleteBySource(
            org.neo4j.driver.TransactionContext transaction,
            GraphDeleteRequest request
    ) {
        if (request.mode() != GraphDeleteMode.DELETE_DERIVED_ONLY) {
            throw new GraphStoreException("SOURCE deletion requires DELETE_DERIVED_ONLY mode");
        }
        String cypher = """
                MATCH (source:HarnessGraphNode)-[relation]->(target:HarnessGraphNode)
                WHERE source.graphId = $graphId
                  AND source.schemaId = $schemaId
                  AND target.graphId = $graphId
                  AND target.schemaId = $schemaId
                  AND relation.sourceId = $sourceId
                WITH collect(relation) AS relations
                FOREACH (item IN relations | DELETE item)
                RETURN size(relations) AS deleted
                """;
        int deleted = transaction.run(cypher, parameters(
                "graphId", request.graphId(),
                "schemaId", request.schemaId(),
                "sourceId", request.targetId())).single().get("deleted").asInt();
        return new GraphDeleteResult(0, deleted);
    }

    private GraphRouteResult findNeighborhood(
            org.neo4j.driver.TransactionContext transaction,
            GraphNeighborhoodRequest request,
            List<String> subjectKeys,
            int maxDepth,
            int relationLimit
    ) {
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        Map<String, GraphRelation> relations = new LinkedHashMap<>();
        Set<String> visitedNodeIds = new LinkedHashSet<>();
        Set<String> frontierKeys = new LinkedHashSet<>(subjectKeys);
        boolean truncated = false;
        int reachedDepth = 0;

        String anchorCypher = """
                MATCH (node:HarnessGraphNode)
                WHERE node.storageKey IN $subjectKeys
                RETURN node
                ORDER BY node.nodeId
                """;
        transaction.run(anchorCypher, parameters("subjectKeys", subjectKeys))
                .list(record -> toGraphNode(record.get("node").asNode()))
                .forEach(node -> {
                    nodes.put(node.nodeId(), node);
                    visitedNodeIds.add(node.nodeId());
                });

        String relationTypeClause = request.relationTypes().isEmpty()
                ? ""
                : ":" + request.relationTypes().stream()
                        .sorted()
                        .map(Neo4jKnowledgeGraphStore::cypherIdentifier)
                        .collect(Collectors.joining("|"));
        String levelCypher = """
                MATCH (frontier:HarnessGraphNode)-[relation%s]-(neighbor:HarnessGraphNode)
                WHERE frontier.storageKey IN $frontierKeys
                  AND neighbor.graphId = $graphId
                  AND neighbor.schemaId = $schemaId
                  AND relation.graphId = $graphId
                  AND relation.schemaId = $schemaId
                  AND NOT relation.relationId IN $seenRelationIds
                WITH DISTINCT relation
                MATCH (source:HarnessGraphNode)-[relation]->(target:HarnessGraphNode)
                RETURN source, relation, target
                ORDER BY relation.relationId
                LIMIT $fetchLimit
                """.formatted(relationTypeClause);

        for (int currentDepth = 1;
             currentDepth <= maxDepth && !frontierKeys.isEmpty() && relations.size() < relationLimit;
             currentDepth++) {
            int remaining = relationLimit - relations.size();
            List<Record> records = transaction.run(levelCypher, parameters(
                            "frontierKeys", List.copyOf(frontierKeys),
                            "graphId", request.graphId(),
                            "schemaId", request.schemaId(),
                            "seenRelationIds", List.copyOf(relations.keySet()),
                            "fetchLimit", remaining + 1))
                    .list();
            if (records.isEmpty()) {
                break;
            }

            reachedDepth = currentDepth;
            if (records.size() > remaining) {
                truncated = true;
                records = List.copyOf(records.subList(0, remaining));
            }

            Set<String> nextFrontierKeys = new LinkedHashSet<>();
            for (Record record : records) {
                GraphNode source = toGraphNode(record.get("source").asNode());
                GraphNode target = toGraphNode(record.get("target").asNode());
                GraphRelation relation = toGraphRelation(record);
                nodes.putIfAbsent(source.nodeId(), source);
                nodes.putIfAbsent(target.nodeId(), target);
                relations.putIfAbsent(relation.relationId(), relation);
                addToNextFrontier(
                        source, request.graphId(), request.schemaId(),
                        visitedNodeIds, nextFrontierKeys);
                addToNextFrontier(
                        target, request.graphId(), request.schemaId(),
                        visitedNodeIds, nextFrontierKeys);
            }
            frontierKeys = nextFrontierKeys;
            if (truncated) {
                break;
            }
        }

        return new GraphRouteResult(
                List.copyOf(nodes.values()),
                List.copyOf(relations.values()),
                List.of(),
                List.of(),
                null,
                Map.of(
                        "provider", "neo4j",
                        "traversal", "breadth-first",
                        "requestedDepth", maxDepth,
                        "reachedDepth", reachedDepth,
                        "truncated", truncated)
        );
    }

    private static void addToNextFrontier(
            GraphNode node,
            String graphId,
            String schemaId,
            Set<String> visitedNodeIds,
            Set<String> nextFrontierKeys
    ) {
        if (visitedNodeIds.add(node.nodeId())) {
            nextFrontierKeys.add(nodeStorageKey(graphId, schemaId, node.nodeId()));
        }
    }

    private GraphNode toGraphNode(Node node) {
        Set<String> labels = new LinkedHashSet<>();
        node.labels().forEach(label -> {
            if (!BASE_NODE_LABEL.equals(label)) {
                labels.add(label);
            }
        });
        return new GraphNode(
                node.get("nodeId").asString(),
                labels,
                valueMapper.nodeProperties(node)
        );
    }

    private GraphRelation toGraphRelation(Record record) {
        Node source = record.get("source").asNode();
        Node target = record.get("target").asNode();
        Relationship relationship = record.get("relation").asRelationship();
        return new GraphRelation(
                relationship.get("relationId").asString(),
                source.get("nodeId").asString(),
                target.get("nodeId").asString(),
                relationship.type(),
                valueMapper.relationProperties(relationship)
        );
    }

    private static String removePropertiesClause(String variable, Set<String> propertyNames) {
        if (propertyNames.isEmpty()) {
            return "";
        }
        return "REMOVE " + propertyNames.stream()
                .sorted()
                .map(propertyName -> variable + ".`" + propertyName.replace("`", "``") + "`")
                .collect(Collectors.joining(", "));
    }

    private static <T> PageResponse<T> page(
            List<T> fetched,
            int limit,
            Function<T, String> cursorExtractor
    ) {
        return PageResponse.fromFetched(fetched, limit, cursorExtractor);
    }

    private static String nodeStorageKey(String graphId, String schemaId, String nodeId) {
        return graphId + '\u001F' + schemaId + '\u001F' + nodeId;
    }

    private static String relationStorageKey(String graphId, String schemaId, String relationId) {
        return graphId + '\u001F' + schemaId + '\u001F' + relationId;
    }

    private static String encodeGraphSpaceCursor(String graphId, String schemaId) {
        String value = graphId.length() + ":" + graphId + schemaId;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static GraphSpaceCursor decodeGraphSpaceCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new GraphSpaceCursor("", "");
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separatorIndex = value.indexOf(':');
            if (separatorIndex <= 0) {
                throw new IllegalArgumentException("Graph space cursor has no graph ID length");
            }
            int graphIdLength = Integer.parseInt(value.substring(0, separatorIndex));
            int graphIdStart = separatorIndex + 1;
            int graphIdEnd = graphIdStart + graphIdLength;
            if (graphIdLength <= 0 || graphIdEnd >= value.length()) {
                throw new IllegalArgumentException("Graph space cursor has invalid field lengths");
            }
            return new GraphSpaceCursor(
                    value.substring(graphIdStart, graphIdEnd),
                    value.substring(graphIdEnd)
            );
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid graph space cursor", e);
        }
    }

    private static String mutationStorageKey(GraphMutationBatch mutationBatch) {
        return mutationBatch.graphId() + '\u001F'
                + mutationBatch.schemaId() + '\u001F'
                + mutationBatch.requestId();
    }

    private static String relationConstraintName(String schemaId, String relationType) {
        String source = schemaId + '\u001F' + relationType;
        return "harness_graph_relation_" + Integer.toUnsignedString(source.hashCode(), 36);
    }

    private static String relationPageIndexName(String schemaId, String relationType) {
        String source = schemaId + '\u001F' + relationType;
        return "harness_graph_relation_page_" + Integer.toUnsignedString(source.hashCode(), 36);
    }

    private static String cypherIdentifier(String value) {
        return "`" + value.replace("`", "``") + "`";
    }

    private record GraphSpaceCursor(String graphId, String schemaId) {
    }
}

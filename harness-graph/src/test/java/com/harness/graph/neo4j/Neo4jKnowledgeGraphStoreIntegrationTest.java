package com.harness.graph.neo4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.graph.config.GraphProvider;
import com.harness.graph.config.GraphSettings;
import com.harness.graph.model.GraphDeleteMode;
import com.harness.graph.model.GraphDeleteRequest;
import com.harness.graph.model.GraphDeleteTarget;
import com.harness.graph.model.GraphMutationBatch;
import com.harness.graph.model.GraphNeighborhoodRequest;
import com.harness.graph.model.GraphNode;
import com.harness.graph.model.GraphNodeKey;
import com.harness.graph.model.GraphNodePageRequest;
import com.harness.graph.model.GraphRelation;
import com.harness.graph.model.GraphRelationPageRequest;
import com.harness.graph.model.GraphSpacePageRequest;
import com.harness.graph.schema.GraphNodeTypeDefinition;
import com.harness.graph.schema.GraphPropertyDefinition;
import com.harness.graph.schema.GraphPropertyType;
import com.harness.graph.schema.GraphRelationTypeDefinition;
import com.harness.graph.schema.GraphSchemaDefinition;
import com.harness.graph.schema.GraphSchemaMode;
import com.harness.graph.schema.GraphSchemaProvider;
import com.harness.graph.schema.GraphSchemaRegistry;
import com.harness.graph.store.GraphStoreException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Neo4jKnowledgeGraphStoreIntegrationTest {

    @Test
    void shouldKeepMutationsTransactionalAndQueriesGraphScoped() {
        String uri = System.getProperty("graph.it.uri", "");
        Assumptions.assumeFalse(uri.isBlank(), "Set -Dgraph.it.uri to run Neo4j integration tests");
        String user = System.getProperty("graph.it.user", "neo4j");
        String password = System.getProperty("graph.it.password", "test-password");
        waitUntilReady(uri, user, password);

        GraphSchemaRegistry registry = registry();
        String graphId = "graph-" + UUID.randomUUID();
        Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
        try (Neo4jKnowledgeGraphStore store =
                     new Neo4jKnowledgeGraphStore(
                             driver,
                             settings(uri, user, password),
                             registry,
                             new ObjectMapper())) {
            GraphMutationBatch mutation = mutation(graphId);

            var first = store.upsertBatch(mutation);
            var retry = store.upsertBatch(mutation);

            assertThat(first.nodeCount()).isEqualTo(3);
            assertThat(first.relationCount()).isEqualTo(2);
            assertThat(retry).isEqualTo(first);

            var firstNodePage = store.listNodes(new GraphNodePageRequest(
                    graphId, "project-graph", "", "", 1, ""));
            assertThat(firstNodePage.items()).hasSize(1);
            assertThat(firstNodePage.pageInfo().hasMore()).isTrue();
            assertThat(firstNodePage.pageInfo().nextCursor()).isNotBlank();
            var secondNodePage = store.listNodes(new GraphNodePageRequest(
                    graphId, "project-graph", "", "", 1, firstNodePage.pageInfo().nextCursor()));
            assertThat(secondNodePage.items()).hasSize(1);
            assertThat(secondNodePage.items().getFirst().nodeId())
                    .isNotEqualTo(firstNodePage.items().getFirst().nodeId());

            var namedNodePage = store.listNodes(new GraphNodePageRequest(
                    graphId, "project-graph", "", "yre", 10, ""));
            assertThat(namedNodePage.items()).extracting(GraphNode::nodeId)
                    .containsExactly("project-1");

            var relationPage = store.listRelations(new GraphRelationPageRequest(
                    graphId, "project-graph", "WORKS_ON", 10, ""));
            assertThat(relationPage.items()).hasSize(2);

            store.upsertBatch(new GraphMutationBatch(
                    "replace-relation-properties",
                    graphId,
                    "project-graph",
                    List.of(),
                    List.of(new GraphRelation(
                            "relation-2",
                            "person-1",
                            "project-2",
                            "WORKS_ON",
                            Map.of("role", "reviewer")))
            ));
            var replacedRelation = store.listRelations(new GraphRelationPageRequest(
                    graphId, "project-graph", "WORKS_ON", 10, "")).items().stream()
                    .filter(relation -> relation.relationId().equals("relation-2"))
                    .findFirst()
                    .orElseThrow();
            assertThat(replacedRelation.properties())
                    .containsEntry("role", "reviewer")
                    .doesNotContainKey("sourceId");

            var graphSpacePage = store.listGraphSpaces(new GraphSpacePageRequest(100, ""));
            while (graphSpacePage.items().stream().noneMatch(summary -> graphId.equals(summary.graphId()))
                    && graphSpacePage.pageInfo().hasMore()) {
                graphSpacePage = store.listGraphSpaces(new GraphSpacePageRequest(
                        100, graphSpacePage.pageInfo().nextCursor()));
            }
            assertThat(graphSpacePage.items())
                    .anySatisfy(summary -> {
                        assertThat(summary.graphId()).isEqualTo(graphId);
                        assertThat(summary.schemaId()).isEqualTo("project-graph");
                        assertThat(summary.nodeCount()).isEqualTo(3);
                        assertThat(summary.relationCount()).isEqualTo(2);
                    });

            var graphResult = store.findNeighborhood(new GraphNeighborhoodRequest(
                    graphId,
                    "project-graph",
                    Set.of("person-1"),
                    Set.of("WORKS_ON"),
                    1,
                    10
            ));
            assertThat(graphResult.nodes()).extracting(GraphNode::nodeId)
                    .contains("person-1", "project-1", "project-2");
            assertThat(graphResult.relations()).extracting(GraphRelation::relationId)
                    .containsExactlyInAnyOrder("relation-1", "relation-2");
            assertThat(graphResult.paths()).isEmpty();
            assertThat(graphResult.metadata())
                    .containsEntry("traversal", "breadth-first")
                    .containsEntry("reachedDepth", 1)
                    .containsEntry("truncated", false);

            var limitedGraphResult = store.findNeighborhood(new GraphNeighborhoodRequest(
                    graphId,
                    "project-graph",
                    Set.of("person-1"),
                    Set.of("WORKS_ON"),
                    1,
                    1
            ));
            assertThat(limitedGraphResult.relations()).hasSize(1);
            assertThat(limitedGraphResult.metadata()).containsEntry("truncated", true);

            assertThat(store.getNode(new GraphNodeKey(
                    "another-graph", "project-graph", "person-1"))).isNull();

            store.upsertBatch(new GraphMutationBatch(
                    "change-relation-structure",
                    graphId,
                    "project-graph",
                    List.of(),
                    List.of(new GraphRelation(
                            "relation-2",
                            "person-1",
                            "project-1",
                            "REVIEWS",
                            Map.of("role", "lead")))
            ));
            var structurallyUpdatedRelations = store.listRelations(new GraphRelationPageRequest(
                    graphId, "project-graph", "", 10, "")).items();
            assertThat(structurallyUpdatedRelations).hasSize(2);
            assertThat(structurallyUpdatedRelations)
                    .filteredOn(relation -> relation.relationId().equals("relation-2"))
                    .singleElement()
                    .satisfies(relation -> {
                        assertThat(relation.relationType()).isEqualTo("REVIEWS");
                        assertThat(relation.sourceNodeId()).isEqualTo("person-1");
                        assertThat(relation.targetNodeId()).isEqualTo("project-1");
                        assertThat(relation.properties()).containsExactly(
                                Map.entry("role", "lead"));
                    });

            GraphMutationBatch invalidMutation = new GraphMutationBatch(
                    "invalid-request",
                    graphId,
                    "project-graph",
                    List.of(new GraphNode(
                            "rolled-back-person", Set.of("Person"), Map.of("name", "Rollback"))),
                    List.of(new GraphRelation(
                            "invalid-relation",
                            "rolled-back-person",
                            "missing-project",
                            "WORKS_ON",
                            Map.of("sourceId", "source-invalid")))
            );
            assertThatThrownBy(() -> store.upsertBatch(invalidMutation))
                    .isInstanceOf(GraphStoreException.class);
            assertThat(store.getNode(new GraphNodeKey(
                    graphId, "project-graph", "rolled-back-person"))).isNull();

            assertThatThrownBy(() -> store.delete(new GraphDeleteRequest(
                    graphId,
                    "project-graph",
                    GraphDeleteTarget.NODE,
                    "person-1",
                    GraphDeleteMode.REJECT_IF_REFERENCED
            ))).isInstanceOf(GraphStoreException.class);

            var sourceDelete = store.delete(new GraphDeleteRequest(
                    graphId,
                    "project-graph",
                    GraphDeleteTarget.SOURCE,
                    "source-1",
                    GraphDeleteMode.DELETE_DERIVED_ONLY
            ));
            assertThat(sourceDelete.deletedRelations()).isEqualTo(1);

            var relationDelete = store.delete(new GraphDeleteRequest(
                    graphId,
                    "project-graph",
                    GraphDeleteTarget.RELATION,
                    "relation-2",
                    GraphDeleteMode.REJECT_IF_REFERENCED
            ));
            assertThat(relationDelete.deletedRelations()).isEqualTo(1);

            var nodeDelete = store.delete(new GraphDeleteRequest(
                    graphId,
                    "project-graph",
                    GraphDeleteTarget.NODE,
                    "person-1",
                    GraphDeleteMode.REJECT_IF_REFERENCED
            ));
            assertThat(nodeDelete.deletedNodes()).isEqualTo(1);

            cleanupGraph(driver, graphId);
        }
    }

    private static void cleanupGraph(Driver driver, String graphId) {
        try (var session = driver.session()) {
            session.executeWrite(transaction -> {
                transaction.run("""
                        MATCH (node:HarnessGraphNode {graphId: $graphId})
                        DETACH DELETE node
                        """, Map.of("graphId", graphId)).consume();
                transaction.run("""
                        MATCH (mutation:HarnessGraphMutation {graphId: $graphId})
                        DELETE mutation
                        """, Map.of("graphId", graphId)).consume();
                return null;
            });
        }
    }

    private static void waitUntilReady(String uri, String user, String password) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < 60; attempt++) {
            try (Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password))) {
                driver.verifyConnectivity();
                return;
            } catch (RuntimeException e) {
                lastFailure = e;
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for Neo4j", interrupted);
                }
            }
        }
        throw new IllegalStateException("Neo4j did not become ready", lastFailure);
    }

    private static GraphMutationBatch mutation(String graphId) {
        return new GraphMutationBatch(
                "request-1",
                graphId,
                "project-graph",
                List.of(
                        new GraphNode("person-1", Set.of("Person"), Map.of("name", "Alex")),
                        new GraphNode("project-1", Set.of("Project"), Map.of("name", "Cyrene")),
                        new GraphNode("project-2", Set.of("Project"), Map.of("name", "Atlas"))
                ),
                List.of(
                        new GraphRelation(
                                "relation-1",
                                "person-1",
                                "project-1",
                                "WORKS_ON",
                                Map.of("role", "maintainer", "sourceId", "source-1")),
                        new GraphRelation(
                                "relation-2",
                                "person-1",
                                "project-2",
                                "WORKS_ON",
                                Map.of("role", "reviewer", "sourceId", "source-2"))
                )
        );
    }

    private static GraphSchemaRegistry registry() {
        GraphPropertyDefinition name = new GraphPropertyDefinition(
                "name", GraphPropertyType.STRING, true, false, true, true);
        GraphPropertyDefinition role = new GraphPropertyDefinition(
                "role", GraphPropertyType.STRING, false, false, true, false);
        GraphPropertyDefinition sourceId = new GraphPropertyDefinition(
                "sourceId", GraphPropertyType.STRING, false, false, true, false);
        GraphSchemaDefinition schema = new GraphSchemaDefinition(
                "project-graph",
                1,
                GraphSchemaMode.STRICT,
                Map.of(
                        "Person", new GraphNodeTypeDefinition("Person", Map.of("name", name)),
                        "Project", new GraphNodeTypeDefinition("Project", Map.of("name", name))
                ),
                Map.of(
                        "WORKS_ON", new GraphRelationTypeDefinition(
                                "WORKS_ON",
                                Set.of("Person"),
                                Set.of("Project"),
                                Map.of("role", role, "sourceId", sourceId)),
                        "REVIEWS", new GraphRelationTypeDefinition(
                                "REVIEWS",
                                Set.of("Person"),
                                Set.of("Project"),
                                Map.of("role", role))
                ),
                1,
                2
        );
        return new GraphSchemaRegistry(List.of(new GraphSchemaProvider() {
            @Override
            public String schemaId() {
                return schema.schemaId();
            }

            @Override
            public GraphSchemaDefinition definition() {
                return schema;
            }
        }));
    }

    private static GraphSettings settings(String uri, String user, String password) {
        return new GraphSettings(
                GraphProvider.NEO4J,
                uri,
                user,
                password,
                "neo4j",
                Duration.ofSeconds(10),
                Duration.ofSeconds(10),
                10,
                20,
                100,
                1,
                2,
                20,
                4_000
        );
    }
}

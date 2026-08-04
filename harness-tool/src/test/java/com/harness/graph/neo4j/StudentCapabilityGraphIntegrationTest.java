package com.harness.graph.neo4j;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.graph.build.CanonicalJsonGraphDataConverter;
import com.harness.graph.build.GraphBuildRequest;
import com.harness.graph.build.GraphBuildService;
import com.harness.graph.build.GraphBuildSourceType;
import com.harness.graph.build.GraphDataConverterRegistry;
import com.harness.graph.config.GraphProvider;
import com.harness.graph.config.GraphSettings;
import com.harness.graph.model.GraphMutationBatch;
import com.harness.graph.model.GraphNeighborhoodRequest;
import com.harness.graph.model.GraphNode;
import com.harness.graph.model.GraphRelation;
import com.harness.graph.schema.GraphNodeTypeDefinition;
import com.harness.graph.schema.GraphPropertyDefinition;
import com.harness.graph.schema.GraphPropertyType;
import com.harness.graph.schema.GraphRelationTypeDefinition;
import com.harness.graph.schema.GraphSchemaDefinition;
import com.harness.graph.schema.GraphSchemaMode;
import com.harness.graph.schema.GraphSchemaProvider;
import com.harness.graph.schema.GraphSchemaRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StudentCapabilityGraphIntegrationTest {

    private static final String GRAPH_ID = "student-capability-demo";
    private static final String SCHEMA_ID = "student-capability-v1";

    @Test
    void shouldStoreAndQueryStudentCapabilityTeacherGraph() throws JsonProcessingException {
        String uri = System.getProperty("graph.it.uri", "");
        Assumptions.assumeFalse(uri.isBlank(), "Set -Dgraph.it.uri to run Neo4j integration tests");
        String user = System.getProperty("graph.it.user", "neo4j");
        String password = System.getProperty("graph.it.password", "test-password");
        ObjectMapper objectMapper = new ObjectMapper();

        try (Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
             Neo4jKnowledgeGraphStore store = new Neo4jKnowledgeGraphStore(
                     driver,
                     settings(uri, user, password),
                     registry(),
                     objectMapper)) {
            driver.verifyConnectivity();

            GraphMutationBatch mutation = mutation();
            ObjectNode source = objectMapper.createObjectNode();
            source.set("nodes", objectMapper.valueToTree(mutation.nodes()));
            source.set("relations", objectMapper.valueToTree(mutation.relations()));
            GraphBuildService buildService = new GraphBuildService(
                    store, GraphDataConverterRegistry.withDefaults(objectMapper));
            var buildResult = buildService.build(new GraphBuildRequest(
                    mutation.requestId(),
                    mutation.graphId(),
                    mutation.schemaId(),
                    GraphBuildSourceType.STRUCTURED,
                    CanonicalJsonGraphDataConverter.CONVERTER_ID,
                    source
            ));
            assertThat(buildResult.nodeCount()).isEqualTo(5);
            assertThat(buildResult.relationCount()).isEqualTo(6);

            var graphResult = store.findNeighborhood(new GraphNeighborhoodRequest(
                    GRAPH_ID,
                    SCHEMA_ID,
                    Set.of("student-xiaoming"),
                    Set.of(),
                    2,
                    20
            ));

            assertThat(graphResult.nodes()).extracting(GraphNode::nodeId)
                    .containsExactlyInAnyOrder(
                            "student-xiaoming",
                            "teacher-li",
                            "capability-self-care",
                            "capability-language-expression",
                            "capability-emotional-regulation"
                    );
            assertThat(graphResult.relations()).extracting(GraphRelation::relationId)
                    .containsExactlyInAnyOrder(
                            "teacher-li-teaches-xiaoming",
                            "xiaoming-has-self-care",
                            "xiaoming-has-language-expression",
                            "xiaoming-has-emotional-regulation",
                            "teacher-li-supports-language-expression",
                            "teacher-li-supports-emotional-regulation"
                    );

            System.out.println("STUDENT_CAPABILITY_GRAPH_RESULT="
                    + objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(graphResult));
        }
    }

    private static GraphMutationBatch mutation() {
        return new GraphMutationBatch(
                "student-capability-demo-v1",
                GRAPH_ID,
                SCHEMA_ID,
                List.of(
                        new GraphNode(
                                "student-xiaoming",
                                Set.of("Student"),
                                Map.of("name", "小明")),
                        new GraphNode(
                                "teacher-li",
                                Set.of("Teacher"),
                                Map.of("name", "李老师")),
                        new GraphNode(
                                "capability-self-care",
                                Set.of("Capability"),
                                Map.of("name", "生活自理", "category", "生活能力")),
                        new GraphNode(
                                "capability-language-expression",
                                Set.of("Capability"),
                                Map.of("name", "语言表达", "category", "沟通能力")),
                        new GraphNode(
                                "capability-emotional-regulation",
                                Set.of("Capability"),
                                Map.of("name", "情绪调节", "category", "情绪能力"))
                ),
                List.of(
                        new GraphRelation(
                                "teacher-li-teaches-xiaoming",
                                "teacher-li",
                                "student-xiaoming",
                                "TEACHES",
                                Map.of("since", "2025-09")),
                        new GraphRelation(
                                "xiaoming-has-self-care",
                                "student-xiaoming",
                                "capability-self-care",
                                "HAS_CAPABILITY",
                                Map.of("level", 3, "status", "稳定")),
                        new GraphRelation(
                                "xiaoming-has-language-expression",
                                "student-xiaoming",
                                "capability-language-expression",
                                "HAS_CAPABILITY",
                                Map.of("level", 2, "status", "发展中")),
                        new GraphRelation(
                                "xiaoming-has-emotional-regulation",
                                "student-xiaoming",
                                "capability-emotional-regulation",
                                "HAS_CAPABILITY",
                                Map.of("level", 1, "status", "需要支持")),
                        new GraphRelation(
                                "teacher-li-supports-language-expression",
                                "teacher-li",
                                "capability-language-expression",
                                "SUPPORTS",
                                Map.of("focus", "主动表达需求")),
                        new GraphRelation(
                                "teacher-li-supports-emotional-regulation",
                                "teacher-li",
                                "capability-emotional-regulation",
                                "SUPPORTS",
                                Map.of("focus", "识别并表达情绪"))
                )
        );
    }

    private static GraphSchemaRegistry registry() {
        GraphPropertyDefinition name = property(
                "name", GraphPropertyType.STRING, true, true, true, true);
        GraphPropertyDefinition category = property(
                "category", GraphPropertyType.STRING, true, false, true, true);
        GraphPropertyDefinition since = property(
                "since", GraphPropertyType.STRING, true, false, true, false);
        GraphPropertyDefinition level = property(
                "level", GraphPropertyType.INTEGER, true, false, true, true);
        GraphPropertyDefinition status = property(
                "status", GraphPropertyType.STRING, true, false, true, true);
        GraphPropertyDefinition focus = property(
                "focus", GraphPropertyType.STRING, true, false, true, false);

        GraphSchemaDefinition schema = new GraphSchemaDefinition(
                SCHEMA_ID,
                1,
                GraphSchemaMode.STRICT,
                Map.of(
                        "Student", new GraphNodeTypeDefinition("Student", Map.of("name", name)),
                        "Teacher", new GraphNodeTypeDefinition("Teacher", Map.of("name", name)),
                        "Capability", new GraphNodeTypeDefinition(
                                "Capability", Map.of("name", name, "category", category))
                ),
                Map.of(
                        "TEACHES", new GraphRelationTypeDefinition(
                                "TEACHES",
                                Set.of("Teacher"),
                                Set.of("Student"),
                                Map.of("since", since)),
                        "HAS_CAPABILITY", new GraphRelationTypeDefinition(
                                "HAS_CAPABILITY",
                                Set.of("Student"),
                                Set.of("Capability"),
                                Map.of("level", level, "status", status)),
                        "SUPPORTS", new GraphRelationTypeDefinition(
                                "SUPPORTS",
                                Set.of("Teacher"),
                                Set.of("Capability"),
                                Map.of("focus", focus))
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

    private static GraphPropertyDefinition property(
            String name,
            GraphPropertyType type,
            boolean required,
            boolean sensitive,
            boolean queryable,
            boolean sortable
    ) {
        return new GraphPropertyDefinition(name, type, required, sensitive, queryable, sortable);
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

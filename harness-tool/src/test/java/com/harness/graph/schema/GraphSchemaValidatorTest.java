package com.harness.graph.schema;

import com.harness.graph.model.GraphMutationBatch;
import com.harness.graph.model.GraphNode;
import com.harness.graph.model.GraphRelation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphSchemaValidatorTest {

    private GraphSchemaValidator validator;

    @BeforeEach
    void setUp() {
        GraphPropertyDefinition name = new GraphPropertyDefinition(
                "name", GraphPropertyType.STRING, true, false, true, true);
        GraphPropertyDefinition role = new GraphPropertyDefinition(
                "role", GraphPropertyType.STRING, false, false, true, false);

        GraphSchemaDefinition schema = new GraphSchemaDefinition(
                "project-v1",
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
                                Map.of("role", role))
                ),
                1,
                2
        );
        GraphSchemaRegistry registry = new GraphSchemaRegistry();
        registry.register(schema);
        validator = new GraphSchemaValidator(registry);
    }

    @Test
    void acceptsValidStrictMutation() {
        GraphMutationBatch mutation = new GraphMutationBatch(
                "request-1",
                "graph-1",
                "project-v1",
                List.of(
                        new GraphNode("person-1", Set.of("Person"), Map.of("name", "Ada")),
                        new GraphNode("project-1", Set.of("Project"), Map.of("name", "Cyrene"))
                ),
                List.of(new GraphRelation(
                        "relation-1",
                        "person-1",
                        "project-1",
                        "WORKS_ON",
                        Map.of("role", "Maintainer")))
        );

        assertThatCode(() -> validator.validate(mutation)).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownProperty() {
        GraphMutationBatch mutation = new GraphMutationBatch(
                "request-2",
                "graph-1",
                "project-v1",
                List.of(new GraphNode(
                        "person-1",
                        Set.of("Person"),
                        Map.of("name", "Ada", "password", "secret"))),
                List.of()
        );

        assertThatThrownBy(() -> validator.validate(mutation))
                .isInstanceOf(GraphSchemaValidationException.class)
                .hasMessageContaining("password");
    }

    @Test
    void rejectsRelationWithInvalidEndpointType() {
        GraphMutationBatch mutation = new GraphMutationBatch(
                "request-3",
                "graph-1",
                "project-v1",
                List.of(
                        new GraphNode("project-1", Set.of("Project"), Map.of("name", "One")),
                        new GraphNode("project-2", Set.of("Project"), Map.of("name", "Two"))
                ),
                List.of(new GraphRelation(
                        "relation-1",
                        "project-1",
                        "project-2",
                        "WORKS_ON",
                        Map.of()))
        );

        assertThatThrownBy(() -> validator.validate(mutation))
                .isInstanceOf(GraphSchemaValidationException.class)
                .hasMessageContaining("source node type");
    }

    @Test
    void rejectsMissingRequiredProperty() {
        GraphMutationBatch mutation = new GraphMutationBatch(
                "request-4",
                "graph-1",
                "project-v1",
                List.of(new GraphNode("person-1", Set.of("Person"), Map.of())),
                List.of()
        );

        assertThatThrownBy(() -> validator.validate(mutation))
                .isInstanceOf(GraphSchemaValidationException.class)
                .hasMessageContaining("name");
    }

    @Test
    void rejectsUnknownSchemaWhenOptionalFilterIsEmpty() {
        assertThatThrownBy(() -> validator.validateNodeLabel("missing-schema", ""))
                .isInstanceOf(GraphSchemaValidationException.class);
        assertThatThrownBy(() -> validator.validateRelationType("missing-schema", ""))
                .isInstanceOf(GraphSchemaValidationException.class);
    }
}

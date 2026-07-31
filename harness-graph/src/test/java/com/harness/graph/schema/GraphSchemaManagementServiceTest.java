package com.harness.graph.schema;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphSchemaManagementServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsYamlSchemaAndControlsRuntimeRegistration() {
        GraphSchemaRegistry registry = new GraphSchemaRegistry();
        GraphSchemaManagementService service = GraphSchemaManagementService.open(temporaryDirectory, registry);

        GraphSchemaDetails created = service.create(GraphSchemaFormat.YAML, yamlSchema(1), false);

        assertThat(created.enabled()).isFalse();
        assertThat(created.format()).isEqualTo(GraphSchemaFormat.YAML);
        assertThat(registry.find("child-capability-v1")).isEmpty();
        assertThat(service.list()).singleElement().satisfies(summary -> {
            assertThat(summary.schemaId()).isEqualTo("child-capability-v1");
            assertThat(summary.enabled()).isFalse();
            assertThat(summary.source()).isEqualTo(GraphSchemaSource.MANAGED);
        });

        service.enable("child-capability-v1");
        assertThat(registry.require("child-capability-v1").version()).isEqualTo(1);

        service.update("child-capability-v1", GraphSchemaFormat.YAML, yamlSchema(2));
        assertThat(registry.require("child-capability-v1").version()).isEqualTo(2);

        service.disable("child-capability-v1");
        assertThat(registry.find("child-capability-v1")).isEmpty();

        service.delete("child-capability-v1");
        assertThat(service.list()).isEmpty();
    }

    @Test
    void reloadsOnlyEnabledManagedSchemasFromDisk() {
        GraphSchemaRegistry firstRegistry = new GraphSchemaRegistry();
        GraphSchemaManagementService firstService =
                GraphSchemaManagementService.open(temporaryDirectory, firstRegistry);
        firstService.create(GraphSchemaFormat.JSON, jsonSchema("active-schema", 1), true);
        firstService.create(GraphSchemaFormat.JSON, jsonSchema("inactive-schema", 1), false);

        GraphSchemaRegistry reloadedRegistry = new GraphSchemaRegistry();
        GraphSchemaManagementService reloadedService =
                GraphSchemaManagementService.open(temporaryDirectory, reloadedRegistry);

        assertThat(reloadedService.list()).hasSize(2);
        assertThat(reloadedRegistry.find("active-schema")).isPresent();
        assertThat(reloadedRegistry.find("inactive-schema")).isEmpty();
    }

    @Test
    void keepsSpiSchemasReadOnly() {
        GraphSchemaDefinition definition = definition("spi-schema", 1);
        GraphSchemaProvider provider = new GraphSchemaProvider() {
            @Override
            public String schemaId() {
                return definition.schemaId();
            }

            @Override
            public GraphSchemaDefinition definition() {
                return definition;
            }
        };
        GraphSchemaRegistry registry = new GraphSchemaRegistry(List.of(provider));
        GraphSchemaManagementService service = GraphSchemaManagementService.open(temporaryDirectory, registry);

        GraphSchemaDetails details = service.get("spi-schema");

        assertThat(details.source()).isEqualTo(GraphSchemaSource.SPI);
        assertThat(details.editable()).isFalse();
        assertThat(details.format()).isEqualTo(GraphSchemaFormat.JAVA);
        assertThatThrownBy(() -> service.disable("spi-schema"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("read-only");
    }

    @Test
    void rejectsChangingSchemaIdDuringUpdateAndDeletingEnabledSchema() {
        GraphSchemaManagementService service =
                GraphSchemaManagementService.open(temporaryDirectory, new GraphSchemaRegistry());
        service.create(GraphSchemaFormat.JSON, jsonSchema("original-schema", 1), true);

        assertThatThrownBy(() -> service.update(
                "original-schema",
                GraphSchemaFormat.JSON,
                jsonSchema("renamed-schema", 2)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must match");
        assertThatThrownBy(() -> service.delete("original-schema"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Disable");
    }

    private static String yamlSchema(int version) {
        return """
                schemaId: child-capability-v1
                version: %d
                mode: STRICT
                nodeTypes:
                  Student:
                    label: Student
                    properties:
                      name:
                        name: name
                        type: STRING
                        required: true
                        sensitive: false
                        queryable: true
                        sortable: true
                relationTypes: {}
                defaultMaxDepth: 1
                maxDepth: 2
                """.formatted(version);
    }

    private static String jsonSchema(String schemaId, int version) {
        return GraphSchemaDocumentCodec.createDefault().render(
                GraphSchemaFormat.JSON,
                definition(schemaId, version)
        );
    }

    private static GraphSchemaDefinition definition(String schemaId, int version) {
        GraphPropertyDefinition name = new GraphPropertyDefinition(
                "name", GraphPropertyType.STRING, true, false, true, true);
        return new GraphSchemaDefinition(
                schemaId,
                version,
                GraphSchemaMode.STRICT,
                Map.of("Student", new GraphNodeTypeDefinition("Student", Map.of("name", name))),
                Map.of(),
                1,
                2
        );
    }
}

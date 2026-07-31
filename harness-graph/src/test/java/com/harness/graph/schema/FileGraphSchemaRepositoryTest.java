package com.harness.graph.schema;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileGraphSchemaRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsOneCanonicalFilePerSchema() throws Exception {
        GraphSchemaDocumentCodec codec = GraphSchemaDocumentCodec.createDefault();
        FileGraphSchemaRepository repository =
                new FileGraphSchemaRepository(temporaryDirectory, codec.jsonMapper());
        StoredGraphSchema first = new StoredGraphSchema(
                false, GraphSchemaFormat.YAML, definition(1));
        StoredGraphSchema second = new StoredGraphSchema(
                true, GraphSchemaFormat.JSON, definition(2));

        repository.save(first);
        repository.save(second);

        assertThat(repository.find("managed-schema")).contains(second);
        assertThat(repository.list()).containsExactly(second);
        try (var files = Files.list(temporaryDirectory)) {
            assertThat(files.filter(Files::isRegularFile).toList())
                    .singleElement()
                    .extracting(path -> path.getFileName().toString())
                    .isEqualTo("managed-schema.schema.json");
        }
    }

    private static GraphSchemaDefinition definition(int version) {
        return new GraphSchemaDefinition(
                "managed-schema",
                version,
                GraphSchemaMode.STRICT,
                Map.of("Student", new GraphNodeTypeDefinition("Student", Map.of())),
                Map.of(),
                1,
                2
        );
    }
}

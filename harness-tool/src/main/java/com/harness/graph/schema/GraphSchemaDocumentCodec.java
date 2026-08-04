package com.harness.graph.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

public final class GraphSchemaDocumentCodec {

    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;

    public GraphSchemaDocumentCodec(ObjectMapper jsonMapper, ObjectMapper yamlMapper) {
        this.jsonMapper = jsonMapper;
        this.yamlMapper = yamlMapper;
    }

    public static GraphSchemaDocumentCodec createDefault() {
        YAMLFactory yamlFactory = YAMLFactory.builder()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .build();
        return new GraphSchemaDocumentCodec(new ObjectMapper(), new ObjectMapper(yamlFactory));
    }

    public GraphSchemaDefinition parse(GraphSchemaFormat format, String content) {
        if (format == null || format == GraphSchemaFormat.JAVA) {
            throw new IllegalArgumentException("format must be JSON or YAML");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("schema content is required");
        }
        try {
            return mapper(format).readValue(content, GraphSchemaDefinition.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Invalid " + format.name() + " graph schema: " + e.getOriginalMessage(), e);
        }
    }

    public String render(GraphSchemaFormat format, GraphSchemaDefinition definition) {
        if (format == GraphSchemaFormat.JAVA) {
            format = GraphSchemaFormat.JSON;
        }
        try {
            return mapper(format).writerWithDefaultPrettyPrinter().writeValueAsString(definition);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to render graph schema: " + definition.schemaId(), e);
        }
    }

    public ObjectMapper jsonMapper() {
        return jsonMapper;
    }

    private ObjectMapper mapper(GraphSchemaFormat format) {
        return format == GraphSchemaFormat.YAML ? yamlMapper : jsonMapper;
    }
}

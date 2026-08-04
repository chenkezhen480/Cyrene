package com.harness.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonSchemaConverterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void toObjectSchema_preservesNestedObjectsAndArrays() throws Exception {
        var source = MAPPER.readTree("""
                {
                  "type": "object",
                  "required": ["profile", "items"],
                  "properties": {
                    "profile": {
                      "type": "object",
                      "required": ["name"],
                      "properties": {
                        "name": {"type": "string"},
                        "age": {"type": "integer"}
                      }
                    },
                    "items": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "required": ["code"],
                        "properties": {
                          "code": {"type": "string"},
                          "tags": {
                            "type": "array",
                            "items": {"type": "string"}
                          }
                        }
                      }
                    },
                    "status": {
                      "type": "string",
                      "enum": ["active", "disabled"]
                    }
                  }
                }
                """);

        JsonObjectSchema result = JsonSchemaConverter.toObjectSchema(source);

        assertThat(result.required()).containsExactly("profile", "items");
        assertThat(result.properties().get("profile")).isInstanceOf(JsonObjectSchema.class);
        JsonObjectSchema profile = (JsonObjectSchema) result.properties().get("profile");
        assertThat(profile.required()).containsExactly("name");
        assertThat(profile.properties().get("name")).isInstanceOf(JsonStringSchema.class);
        assertThat(profile.properties().get("age")).isInstanceOf(JsonIntegerSchema.class);

        assertThat(result.properties().get("items")).isInstanceOf(JsonArraySchema.class);
        JsonArraySchema items = (JsonArraySchema) result.properties().get("items");
        assertThat(items.items()).isInstanceOf(JsonObjectSchema.class);
        JsonObjectSchema item = (JsonObjectSchema) items.items();
        assertThat(item.required()).containsExactly("code");
        assertThat(item.properties().get("tags")).isInstanceOf(JsonArraySchema.class);
        assertThat(((JsonArraySchema) item.properties().get("tags")).items())
                .isInstanceOf(JsonStringSchema.class);

        assertThat(result.properties().get("status")).isInstanceOf(JsonEnumSchema.class);
        assertThat(((JsonEnumSchema) result.properties().get("status")).enumValues())
                .containsExactly("active", "disabled");
    }

    @Test
    void toObjectSchema_rejectsNonObjectRoot() throws Exception {
        var source = MAPPER.readTree("""
                {"type":"array","items":{"type":"string"}}
                """);

        assertThatThrownBy(() -> JsonSchemaConverter.toObjectSchema(source))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("root must be an object");
    }

    @Test
    void toObjectSchema_rejectsArrayWithoutItems() throws Exception {
        var source = MAPPER.readTree("""
                {
                  "type":"object",
                  "properties":{"values":{"type":"array"}}
                }
                """);

        assertThatThrownBy(() -> JsonSchemaConverter.toObjectSchema(source))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must define items");
    }
}

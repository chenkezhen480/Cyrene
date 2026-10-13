package com.harness.tool.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.ApiEndpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiSpecParserTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    @TempDir
    Path tempDir;

    @Test
    void resolvesNestedLocalBodyReferencesAndRetainsSchemaConstraints() throws Exception {
        ApiEndpoint endpoint = parse("""
                {"type":"object","properties":{
                  "reason":{"$ref":"#/components/schemas/Text"},
                  "repeatReason":{"$ref":"#/components/schemas/Text"},
                  "tags":{"type":"array","items":{"$ref":"#/components/schemas/Text"}}},
                  "required":["reason"]}
                """, true, "{\"Text\":{\"type\":\"string\",\"minLength\":1}}");

        assertEquals(mapper.readTree("""
                {"type":"object","properties":{
                  "reason":{"type":"string","minLength":1},
                  "repeatReason":{"type":"string","minLength":1},
                  "tags":{"type":"array","items":{"type":"string","minLength":1}}},
                  "required":["reason"]}
                """), endpoint.parameters().path("properties").path("__body__"));
        assertEquals(mapper.readTree("[\"id\",\"__body__\"]"), endpoint.parameters().path("required"));
    }

    @Test
    void optionalRequestBodyIsNotMarkedRequired() throws Exception {
        ApiEndpoint endpoint = parse("{\"type\":\"object\"}", false, "{}");

        assertEquals(mapper.readTree("[\"id\"]"), endpoint.parameters().path("required"));
    }

    @Test
    void preservesReferencedQueryParameterConstraintsAndArrayItems() throws Exception {
        ApiEndpoint endpoint = parseRoot(mapper.readTree("""
                {"openapi":"3.0.3","paths":{"/records":{"get":{"operationId":"listRecords","parameters":[
                  {"name":"limit","in":"query","schema":{"$ref":"#/components/schemas/Limit"}},
                  {"name":"tags","in":"query","schema":{"type":"array","items":{"$ref":"#/components/schemas/Tag"}}}
                ]}}},"components":{"schemas":{
                  "Limit":{"type":"integer","minimum":1,"maximum":100,"format":"int32"},
                  "Tag":{"type":"string","enum":["open","done"]}}}}
                """));

        assertEquals(mapper.readTree("""
                {"limit":{"type":"integer","minimum":1,"maximum":100,"format":"int32","in":"query"},
                 "tags":{"type":"array","items":{"type":"string","enum":["open","done"]},"in":"query"}}
                """), endpoint.parameters().path("properties"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"#/components/schemas/Missing", "https://example.invalid/schema.json",
            "other.json#/components/schemas/Text", "#not-a-pointer", ""})
    void rejectsMissingExternalAndInvalidReferences(String reference) throws Exception {
        String schema = mapper.createObjectNode().put("$ref", reference).toString();

        IOException error = assertThrows(IOException.class, () -> parse(schema, true, "{}"));

        assertTrue(error.getMessage().toLowerCase().contains("reference"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "42", "[]"})
    void rejectsNonStringReferences(String reference) {
        assertThrows(IOException.class, () -> parse("{\"$ref\":" + reference + "}", true, "{}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "42", "[]"})
    void rejectsReferencesToNonSchemaTargets(String target) {
        assertThrows(IOException.class, () -> parse("{\"$ref\":\"#/components/schemas/Text\"}", true,
                "{\"Text\":" + target + "}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"Text\":{\"type\":\"object\",\"properties\":{\"child\":{\"$ref\":\"#/components/schemas/Text\"}}}}",
            "{\"Text\":{\"$ref\":\"#/components/schemas/Other\"},\"Other\":{\"$ref\":\"#/components/schemas/Text\"}}"})
    void rejectsCyclicReferencesWithoutUnboundedExpansion(String schemas) {
        IOException error = assertThrows(IOException.class,
                () -> parse("{\"$ref\":\"#/components/schemas/Text\"}", true, schemas));

        assertTrue(error.getMessage().toLowerCase().contains("cyclic"));
    }

    private ApiEndpoint parse(String bodySchema, boolean required, String schemas) throws IOException {
        JsonNode root = mapper.readTree("""
                {"openapi":"3.0.3","servers":[{"url":"http://127.0.0.1"}],"paths":{
                  "/records/{id}":{"put":{"operationId":"updateRecord","parameters":[
                    {"name":"id","in":"path","required":true,"schema":{"type":"string"}}],
                    "requestBody":{"required":%s,"content":{"application/json":{"schema":%s}}}}}},
                  "components":{"schemas":%s}}
                """.formatted(required, bodySchema, schemas));
        return parseRoot(root);
    }

    private ApiEndpoint parseRoot(JsonNode root) throws IOException {
        Path spec = tempDir.resolve("openapi.json");
        Files.writeString(spec, root.toString());
        return new OpenApiSpecParser().parse(spec, tempDir).endpoints().getFirst();
    }
}

package com.harness.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.exception.StructuredOutputException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredOutputValidationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StructuredOutputSchemaValidator schemaValidator =
            new StructuredOutputSchemaValidator(
                    objectMapper,
                    new StructuredOutputSchemaValidator.Limits(4096, 5, 10, 10));
    private final StructuredOutputValueValidator valueValidator =
            new StructuredOutputValueValidator(objectMapper);

    @Test
    void acceptsStrictSchemaAndValidValue() throws Exception {
        var schema = objectMapper.readTree("""
                {
                  "type":"object",
                  "properties":{
                    "eligible":{"type":"boolean"},
                    "reason":{"type":"string"}
                  },
                  "required":["eligible","reason"],
                  "additionalProperties":false
                }
                """);

        var contract = schemaValidator.validate("customerDecision", schema, true);
        var value = valueValidator.parseAndValidate(
                "{\"eligible\":true,\"reason\":\"qualified\"}",
                contract.schema());

        assertThat(value.path("eligible").asBoolean()).isTrue();
        assertThat(value.path("reason").asText()).isEqualTo("qualified");
    }

    @Test
    void rejectsReferencesAndUnknownKeywordsInsteadOfWeakeningSchema() throws Exception {
        var schemaWithRef = objectMapper.readTree("""
                {
                  "type":"object",
                  "properties":{"customer":{"$ref":"https://example.com/schema.json"}},
                  "required":["customer"],
                  "additionalProperties":false
                }
                """);
        var schemaWithIgnoredConstraint = objectMapper.readTree("""
                {
                  "type":"object",
                  "properties":{"score":{"type":"number","minimum":0}},
                  "required":["score"],
                  "additionalProperties":false
                }
                """);

        assertThatThrownBy(() -> schemaValidator.validate("refSchema", schemaWithRef, true))
                .isInstanceOf(StructuredOutputException.class)
                .extracting(error -> ((StructuredOutputException) error).code())
                .isEqualTo(StructuredOutputException.Code.STRUCTURED_OUTPUT_SCHEMA_INVALID);
        assertThatThrownBy(() -> schemaValidator.validate(
                "constraintSchema", schemaWithIgnoredConstraint, true))
                .isInstanceOf(StructuredOutputException.class)
                .hasMessageContaining("Unsupported JSON Schema keyword");
    }

    @Test
    void distinguishesInvalidJsonFromSchemaMismatch() throws Exception {
        var schema = objectMapper.readTree("""
                {
                  "type":"object",
                  "properties":{"eligible":{"type":"boolean"}},
                  "required":["eligible"],
                  "additionalProperties":false
                }
                """);

        assertThatThrownBy(() -> valueValidator.parseAndValidate("not-json", schema))
                .isInstanceOf(StructuredOutputException.class)
                .extracting(error -> ((StructuredOutputException) error).code())
                .isEqualTo(StructuredOutputException.Code.STRUCTURED_OUTPUT_INVALID_JSON);
        assertThatThrownBy(() -> valueValidator.parseAndValidate(
                "{\"eligible\":\"yes\",\"extra\":1}", schema))
                .isInstanceOf(StructuredOutputException.class)
                .extracting(error -> ((StructuredOutputException) error).code())
                .isEqualTo(StructuredOutputException.Code.STRUCTURED_OUTPUT_SCHEMA_MISMATCH);
    }

    @Test
    void enforcesStrictObjectRequirementsAndConfiguredLimits() throws Exception {
        var optionalProperty = objectMapper.readTree("""
                {
                  "type":"object",
                  "properties":{"name":{"type":"string"}},
                  "required":[],
                  "additionalProperties":false
                }
                """);
        var tooManyProperties = objectMapper.readTree("""
                {
                  "type":"object",
                  "properties":{
                    "p1":{"type":"string"},"p2":{"type":"string"},
                    "p3":{"type":"string"},"p4":{"type":"string"},
                    "p5":{"type":"string"},"p6":{"type":"string"},
                    "p7":{"type":"string"},"p8":{"type":"string"},
                    "p9":{"type":"string"},"p10":{"type":"string"},
                    "p11":{"type":"string"}
                  },
                  "required":["p1","p2","p3","p4","p5","p6","p7","p8","p9","p10","p11"],
                  "additionalProperties":false
                }
                """);

        assertThatThrownBy(() -> schemaValidator.validate(
                "optionalSchema", optionalProperty, true))
                .isInstanceOf(StructuredOutputException.class)
                .hasMessageContaining("require every property");
        assertThatThrownBy(() -> schemaValidator.validate(
                "largeSchema", tooManyProperties, true))
                .isInstanceOf(StructuredOutputException.class)
                .hasMessageContaining("property limit");
    }
}

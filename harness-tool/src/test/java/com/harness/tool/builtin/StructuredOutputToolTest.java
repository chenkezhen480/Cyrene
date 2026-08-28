package com.harness.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.FinalOutputContract;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredOutputToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void chatToolKeepsOneStableOpenObjectSchema() throws Exception {
        StructuredOutputTool first = StructuredOutputTool.chatBlock();
        StructuredOutputTool second = StructuredOutputTool.chatBlock();

        assertThat(first.spec()).isEqualTo(second.spec());
        assertThat(first.execute(objectMapper.readTree("{\"rows\":[1,2]}")))
                .isEqualTo("{\"rows\":[1,2]}");
    }

    @Test
    void terminalToolUsesAndValidatesRequestSchema() throws Exception {
        var schema = objectMapper.readTree("""
                {
                  "type":"object",
                  "properties":{"eligible":{"type":"boolean"}},
                  "required":["eligible"],
                  "additionalProperties":false
                }
                """);
        StructuredOutputTool tool = StructuredOutputTool.terminal(
                new FinalOutputContract.JsonSchema("decision", schema, true));

        assertThat(tool.spec().parameters()).isEqualTo(schema);
        assertThat(tool.execute(objectMapper.readTree("{\"eligible\":true}")))
                .isEqualTo("{\"eligible\":true}");
        assertThatThrownBy(() -> tool.execute(
                objectMapper.readTree("{\"eligible\":\"yes\"}")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("does not match");
    }
}

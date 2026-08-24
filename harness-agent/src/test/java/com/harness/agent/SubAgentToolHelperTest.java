package com.harness.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.exception.ToolExecutionException;
import com.harness.provider.LangChainJsonSchemaMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubAgentToolHelperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void awaitToolSchemaConvertsWithoutNullProperties() {
        var schema = new AwaitSubAgentsTool(null).spec().parameters();

        assertThat(schema.path("properties").path("return_when").path("enum").isArray())
                .isTrue();
        assertThat(schema.path("properties").path("on_timeout").path("enum").isArray())
                .isTrue();
        assertThat(LangChainJsonSchemaMapper.toObjectSchema(schema)).isNotNull();
    }

    @Test
    void unknownTaskIdsAreExplicitErrors() {
        SubAgentRunScope scope = new SubAgentRunScope("run-1", 4);

        assertThatThrownBy(() -> SubAgentToolHelper.resolveTaskRecords(
                scope, List.of("missing-1", "missing-2"), "get_subagents"))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("Unknown task IDs: missing-1, missing-2");
    }

    @Test
    void parentResultContainsContractSummaryButNoReactSteps() throws Exception {
        ContractValidation validation = new ContractValidation(
                true, false, ContractValidation.Status.FAILED_CONTRACT,
                List.of("Required tool did not complete successfully: report_tool"));
        SubAgentCompletionContractValidator.Evaluation evaluation =
                new SubAgentCompletionContractValidator.Evaluation(
                        List.of(), ToolExecutionSummary.empty(), validation, null);
        SubAgentResult result = SubAgentResult.incomplete(
                "task-1", "partial", evaluation, 9, "trace-1");
        var output = objectMapper.createObjectNode();

        SubAgentToolHelper.serializeResult(output, result, objectMapper);

        assertThat(output.path("status").asText()).isEqualTo("INCOMPLETE");
        assertThat(output.path("sub_trace_id").asText()).isEqualTo("trace-1");
        assertThat(output.path("contract_validation").path("status").asText())
                .isEqualTo("FAILED_CONTRACT");
        assertThat(output.has("steps")).isFalse();
        assertThat(output.has("step_details")).isFalse();
        assertThat(output.toString()).doesNotContain("observation");
    }
}

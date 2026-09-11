package com.harness.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultTest {

    @Test
    void ok_createsSuccessResult() {
        var result = ToolResult.ok("call-1", "web_search", "search results", 150);

        assertThat(result.toolCallId()).isEqualTo("call-1");
        assertThat(result.toolName()).isEqualTo("web_search");
        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("search results");
        assertThat(result.error()).isNull();
        assertThat(result.durationMs()).isEqualTo(150);
        assertThat(result.executionStatus()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(result.resultStatus()).isEqualTo(ResultStatus.AVAILABLE);
    }

    @Test
    void ok_withStatus_createsResultWithStatus() {
        var result = ToolResult.ok(
                "call-3", "knowledge_search", "some context", 200,
                ResultStatus.LOW_RELEVANCE);

        assertThat(result.success()).isTrue();
        assertThat(result.resultStatus()).isEqualTo(ResultStatus.LOW_RELEVANCE);
    }

    @Test
    void fail_createsFailureResult() {
        var result = ToolResult.fail("call-2", "code_execution", "timeout error", 3000);

        assertThat(result.toolCallId()).isEqualTo("call-2");
        assertThat(result.toolName()).isEqualTo("code_execution");
        assertThat(result.success()).isFalse();
        assertThat(result.output()).isNull();
        assertThat(result.error()).isEqualTo("timeout error");
        assertThat(result.durationMs()).isEqualTo(3000);
        assertThat(result.resultStatus()).isNull();
        assertThat(result.executionStatus()).isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    void verified_requiresBoundedValidationEvidence() {
        var result = ToolResult.verified(
                "call-4", "business_api", ToolOutput.text("validated"), 20,
                "business-api", "response contract and business status verified");

        assertThat(result.resultStatus()).isEqualTo(ResultStatus.VERIFIED);
        assertThat(result.validationSource()).isEqualTo("business-api");
        assertThat(result.validationReason()).contains("business status");
    }

    @Test
    void legacySuccessStatus_deserializesAsAvailable() throws Exception {
        ToolResult result = new ObjectMapper().readValue("""
                {
                  "toolCallId":"call-old",
                  "toolName":"legacy",
                  "success":true,
                  "output":"legacy output",
                  "durationMs":5,
                  "status":"SUCCESS"
                }
                """, ToolResult.class);

        assertThat(result.executionStatus()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(result.resultStatus()).isEqualTo(ResultStatus.AVAILABLE);
        assertThat(result.success()).isTrue();
    }
}

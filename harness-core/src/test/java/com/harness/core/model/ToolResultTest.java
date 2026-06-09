package com.harness.core.model;

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
    }
}

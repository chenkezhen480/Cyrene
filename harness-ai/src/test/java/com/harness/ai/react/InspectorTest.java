package com.harness.ai.react;

import com.harness.core.model.ReActStep.InspectionResult;
import com.harness.core.model.ReActStep.InspectionResult.InspectionStatus;
import com.harness.core.model.ToolCall;
import com.harness.core.model.ToolResult;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InspectorTest {

    Inspector inspector;

    @BeforeEach
    void setUp() {
        inspector = new Inspector();
    }

    private ToolCall toolCall(String name) {
        return ToolCall.of(name, new TextNode("{}"));
    }

    @Test
    void inspect_nullResults_returnsPass() {
        var result = inspector.inspect(List.of(toolCall("test")), null);
        assertThat(result.status()).isEqualTo(InspectionStatus.PASS);
    }

    @Test
    void inspect_emptyResults_returnsPass() {
        var result = inspector.inspect(List.of(toolCall("test")), List.of());
        assertThat(result.status()).isEqualTo(InspectionStatus.PASS);
    }

    @Test
    void inspect_toolError_returnsToolError() {
        ToolCall call = toolCall("db_query");
        ToolResult result = ToolResult.fail("id1", "db_query", "connection refused", 100);

        var inspection = inspector.inspect(List.of(call), List.of(result));

        assertThat(inspection.status()).isEqualTo(InspectionStatus.TOOL_ERROR);
        assertThat(inspection.reason()).contains("db_query");
        assertThat(inspection.reason()).contains("connection refused");
    }

    @Test
    void inspect_exceptionTrace_returnsNeedsRetry() {
        ToolCall call = toolCall("search");
        ToolResult result = ToolResult.ok("id1", "search",
                "java.lang.NullPointerException\n\tat com.example.Service.method(Service.java:42)", 100);

        var inspection = inspector.inspect(List.of(call), List.of(result));

        assertThat(inspection.status()).isEqualTo(InspectionStatus.NEEDS_RETRY);
        assertThat(inspection.reason()).contains("search");
    }

    @Test
    void inspect_emptyOutput_returnsWrongTool() {
        ToolCall call = toolCall("fetch");
        ToolResult result = ToolResult.ok("id1", "fetch", "", 100);

        var inspection = inspector.inspect(List.of(call), List.of(result));

        assertThat(inspection.status()).isEqualTo(InspectionStatus.WRONG_TOOL);
        assertThat(inspection.reason()).contains("fetch");
    }

    @Test
    void inspect_nullOutput_returnsWrongTool() {
        ToolCall call = toolCall("fetch");
        ToolResult result = new ToolResult("id1", "fetch", true, null, null, 100);

        var inspection = inspector.inspect(List.of(call), List.of(result));

        assertThat(inspection.status()).isEqualTo(InspectionStatus.WRONG_TOOL);
    }

    @Test
    void inspect_shortOutput_returnsInsufficient() {
        ToolCall call = toolCall("search");
        ToolResult result = ToolResult.ok("id1", "search", "ok", 100);

        var inspection = inspector.inspect(List.of(call), List.of(result));

        assertThat(inspection.status()).isEqualTo(InspectionStatus.INSUFFICIENT);
        assertThat(inspection.reason()).contains("short output");
    }

    @Test
    void inspect_noResultsPhrase_returnsInsufficient() {
        ToolCall call = toolCall("search");
        ToolResult result = ToolResult.ok("id1", "search",
                "I searched the database thoroughly but no results found for your query", 100);

        var inspection = inspector.inspect(List.of(call), List.of(result));

        assertThat(inspection.status()).isEqualTo(InspectionStatus.INSUFFICIENT);
        assertThat(inspection.reason()).contains("no results");
    }

    @Test
    void inspect_goodOutput_returnsPass() {
        ToolCall call = toolCall("search");
        ToolResult result = ToolResult.ok("id1", "search",
                "Found 15 matching records in the database with detailed information about each entry", 100);

        var inspection = inspector.inspect(List.of(call), List.of(result));

        assertThat(inspection.status()).isEqualTo(InspectionStatus.PASS);
    }

    @Test
    void buildInspectionHint_passStatus_returnsNull() {
        var result = new InspectionResult(InspectionStatus.PASS, "ok");
        assertThat(Inspector.buildInspectionHint(result)).isNull();
    }

    @Test
    void buildInspectionHint_null_returnsNull() {
        assertThat(Inspector.buildInspectionHint(null)).isNull();
    }

    @Test
    void buildInspectionHint_toolError_returnsHint() {
        var result = new InspectionResult(InspectionStatus.TOOL_ERROR, "db failed");
        String hint = Inspector.buildInspectionHint(result);

        assertThat(hint).contains("[Inspection]");
        assertThat(hint).contains("Tool error");
        assertThat(hint).contains("db failed");
    }

    @Test
    void buildInspectionHint_wrongTool_returnsHint() {
        var result = new InspectionResult(InspectionStatus.WRONG_TOOL, "empty output");
        String hint = Inspector.buildInspectionHint(result);

        assertThat(hint).contains("Wrong tool");
    }

    @Test
    void buildInspectionHint_insufficient_returnsHint() {
        var result = new InspectionResult(InspectionStatus.INSUFFICIENT, "too short");
        String hint = Inspector.buildInspectionHint(result);

        assertThat(hint).contains("Insufficient");
    }

    @Test
    void buildInspectionHint_needsRetry_returnsHint() {
        var result = new InspectionResult(InspectionStatus.NEEDS_RETRY, "exception trace");
        String hint = Inspector.buildInspectionHint(result);

        assertThat(hint).contains("Retry needed");
    }
}

package com.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ToolCall;
import com.harness.core.model.ToolResult;
import com.harness.core.model.ToolSpec;
import com.harness.env.EnvConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolExecutorTest {

    static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock ToolRegistry registry;

    ToolExecutor executor;

    @BeforeEach
    void setUp() {
        EnvConfig.init(Map.of("HARNESS_RISK_CONFIRM_TOOLS", "file_delete,db_execute"));
        executor = new ToolExecutor(registry);
    }

    private Tool createTool(String name, String output) {
        ObjectNode params = MAPPER.createObjectNode();
        ToolSpec spec = new ToolSpec(name, "desc", params);
        return new Tool() {
            @Override public ToolSpec spec() { return spec; }
            @Override public String execute(JsonNode args) { return output; }
        };
    }

    private ToolCall toolCall(String name) {
        return ToolCall.of(name, MAPPER.createObjectNode());
    }

    @Test
    void execute_toolFound_returnsOk() {
        Tool tool = createTool("search", "results found");
        when(registry.get("search")).thenReturn(tool);

        ToolResult result = executor.execute(toolCall("search"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("results found");
        assertThat(result.toolName()).isEqualTo("search");
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void execute_toolNotFound_returnsFail() {
        when(registry.get("missing")).thenReturn(null);

        ToolResult result = executor.execute(toolCall("missing"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Tool not found");
        assertThat(result.error()).contains("missing");
    }

    @Test
    void execute_toolThrowsToolExecutionException_returnsFail() {
        Tool tool = new Tool() {
            @Override public ToolSpec spec() {
                return new ToolSpec("fail", "desc", MAPPER.createObjectNode());
            }
            @Override public String execute(JsonNode args) {
                throw new ToolExecutionException("fail", "connection timeout");
            }
        };
        when(registry.get("fail")).thenReturn(tool);

        ToolResult result = executor.execute(toolCall("fail"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("connection timeout");
    }

    @Test
    void execute_toolThrowsUnexpectedException_returnsFail() {
        Tool tool = new Tool() {
            @Override public ToolSpec spec() {
                return new ToolSpec("crash", "desc", MAPPER.createObjectNode());
            }
            @Override public String execute(JsonNode args) {
                throw new RuntimeException("NPE");
            }
        };
        when(registry.get("crash")).thenReturn(tool);

        ToolResult result = executor.execute(toolCall("crash"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Unexpected error");
        assertThat(result.error()).contains("NPE");
    }

    @Test
    void requiresConfirmation_configured_returnsTrue() {
        assertThat(executor.requiresConfirmation("file_delete")).isTrue();
        assertThat(executor.requiresConfirmation("db_execute")).isTrue();
    }

    @Test
    void requiresConfirmation_notConfigured_returnsFalse() {
        assertThat(executor.requiresConfirmation("search")).isFalse();
    }
}

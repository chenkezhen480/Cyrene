package com.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ToolCall;
import com.harness.core.model.ToolResult;
import com.harness.core.model.ToolSpec;
import com.harness.core.env.EnvConfig;
import com.harness.tool.confirmation.ConfirmationManager;
import com.harness.tool.confirmation.ConfirmationDecision;
import com.harness.tool.confirmation.ConfirmationExecutionContext;
import com.harness.tool.confirmation.ConfirmationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

class ToolExecutorTest {

    static final ObjectMapper MAPPER = new ObjectMapper();

    ToolExecutor executor;
    ConfirmationManager confirmationManager;

    @BeforeEach
    void setUp() {
        EnvConfig.init(Map.of("HARNESS_RISK_CONFIRM_TOOLS", "file_delete,db_execute"));
        confirmationManager = new ConfirmationManager(Duration.ofMinutes(5));
        executor = new ToolExecutor(confirmationManager);
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

        ToolResult result = executor.executeAuthorized(toolCall("search"), tool, null);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("results found");
        assertThat(result.toolName()).isEqualTo("search");
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void executeAuthorized_usesToolResolvedByRequestRegistry() {
        Tool requestAuthorizedTool = createTool("search", "request-scoped result");
        ToolResult result = executor.executeAuthorized(
                toolCall("search"), requestAuthorizedTool, null);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("request-scoped result");
    }

    @Test
    void executeAuthorized_rejectsNameMismatch() {
        ToolResult result = executor.executeAuthorized(
                toolCall("search"), createTool("different", "should not run"), null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("name mismatch");
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
        ToolResult result = executor.executeAuthorized(toolCall("fail"), tool, null);

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
        ToolResult result = executor.executeAuthorized(toolCall("crash"), tool, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Unexpected error");
        assertThat(result.error()).contains("NPE");
    }

    @Test
    void execute_configuredConfirmationRequired_blocksToolExecution() {
        AtomicBoolean executed = new AtomicBoolean();
        Tool tool = new Tool() {
            @Override public ToolSpec spec() {
                return new ToolSpec("file_delete", "desc", MAPPER.createObjectNode());
            }
            @Override public String execute(JsonNode args) {
                executed.set(true);
                return "deleted";
            }
        };
        ToolResult result = executor.executeAuthorized(toolCall("file_delete"), tool, null);

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo(ToolResult.ResultStatus.CONFIRMATION_REQUIRED);
        assertThat(executed).isFalse();
    }

    @Test
    void execute_toolSpecConfirmationRequired_blocksToolExecution() {
        AtomicBoolean executed = new AtomicBoolean();
        Tool tool = new Tool() {
            @Override public ToolSpec spec() {
                return new ToolSpec("dangerous", "desc", MAPPER.createObjectNode(),
                        Set.of("write"), true);
            }
            @Override public String execute(JsonNode args) {
                executed.set(true);
                return "changed";
            }
        };
        ToolResult result = executor.executeAuthorized(toolCall("dangerous"), tool, null);

        assertThat(result.status()).isEqualTo(ToolResult.ResultStatus.CONFIRMATION_REQUIRED);
        assertThat(executed).isFalse();
    }

    @Test
    void execute_argumentAwareConfirmation_blocksOnlyMatchingArguments() {
        AtomicBoolean executed = new AtomicBoolean();
        Tool tool = new ArgumentAwareConfirmationTool() {
            @Override public ToolSpec spec() {
                return new ToolSpec(
                        "browser", "browser action", MAPPER.createObjectNode());
            }
            @Override public boolean requiresConfirmation(JsonNode arguments) {
                return "click".equals(arguments.path("action").asText());
            }
            @Override public String execute(JsonNode args) {
                executed.set(true);
                return "done";
            }
        };
        ToolResult observeResult = executor.executeAuthorized(
                ToolCall.of("browser", MAPPER.createObjectNode().put("action", "observe")),
                tool,
                null);
        assertThat(observeResult.success()).isTrue();

        executed.set(false);
        ToolResult clickResult = executor.executeAuthorized(
                ToolCall.of("browser", MAPPER.createObjectNode().put("action", "click")),
                tool,
                null);
        assertThat(clickResult.status())
                .isEqualTo(ToolResult.ResultStatus.CONFIRMATION_REQUIRED);
        assertThat(executed).isFalse();
    }

    @Test
    void execute_approvedInteractiveConfirmation_executesOriginalCall() throws Exception {
        AtomicBoolean executed = new AtomicBoolean();
        AtomicBoolean executionStarted = new AtomicBoolean();
        AtomicReference<ConfirmationRequest> pendingRequest = new AtomicReference<>();
        AtomicReference<ConfirmationDecision> resolvedDecision = new AtomicReference<>();
        CountDownLatch requestCreated = new CountDownLatch(1);
        Tool tool = new Tool() {
            @Override public ToolSpec spec() {
                return new ToolSpec("dangerous", "Delete the selected item",
                        MAPPER.createObjectNode(), Set.of("write"), true);
            }
            @Override public String execute(JsonNode args) {
                executed.set(true);
                return args.path("target").asText();
            }
        };
        ToolCall call = ToolCall.of(
                "dangerous", MAPPER.createObjectNode().put("target", "item-1"));
        ConfirmationExecutionContext context = new ConfirmationExecutionContext(
                "user-1",
                "session-1",
                null,
                request -> {
                    pendingRequest.set(request);
                    requestCreated.countDown();
                },
                (request, decision) -> resolvedDecision.set(decision),
                (toolName, arguments) -> executionStarted.set(true));

        CompletableFuture<ToolResult> resultFuture = CompletableFuture.supplyAsync(
                () -> executor.executeAuthorized(call, tool, context));
        assertThat(requestCreated.await(2, TimeUnit.SECONDS)).isTrue();
        confirmationManager.approve(
                pendingRequest.get().requestId(), "user-1", "session-1");

        ToolResult result = resultFuture.get(2, TimeUnit.SECONDS);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("item-1");
        assertThat(resolvedDecision.get()).isEqualTo(ConfirmationDecision.APPROVED);
        assertThat(executionStarted).isTrue();
        assertThat(executed).isTrue();
    }

    @Test
    void execute_toolSetsStatus_statusIsAttached() {
        // Tool that sets explicit status via ThreadLocal
        Tool tool = new Tool() {
            @Override public ToolSpec spec() {
                return new ToolSpec("kb", "desc", MAPPER.createObjectNode());
            }
            @Override public String execute(JsonNode args) {
                ToolResult.setCurrentStatus(ToolResult.ResultStatus.EMPTY);
                return "No results found";
            }
        };
        ToolResult result = executor.executeAuthorized(toolCall("kb"), tool, null);

        assertThat(result.success()).isTrue();
        assertThat(result.status()).isEqualTo(ToolResult.ResultStatus.EMPTY);
    }

    @Test
    void execute_toolDoesNotSetStatus_statusIsNull() {
        Tool tool = createTool("search", "results found");
        ToolResult result = executor.executeAuthorized(toolCall("search"), tool, null);

        assertThat(result.success()).isTrue();
        assertThat(result.status()).isNull();
    }

    @Test
    void execute_failedToolStatus_doesNotLeakIntoNextExecution() {
        Tool failingTool = new Tool() {
            @Override public ToolSpec spec() {
                return new ToolSpec("fail_with_status", "desc", MAPPER.createObjectNode());
            }
            @Override public String execute(JsonNode args) {
                ToolResult.setCurrentStatus(ToolResult.ResultStatus.SUCCESS);
                throw new ToolExecutionException("fail_with_status", "failed after setting status");
            }
        };
        Tool nextTool = createTool("next", "ok");
        ToolResult failed = executor.executeAuthorized(
                toolCall("fail_with_status"), failingTool, null);
        ToolResult next = executor.executeAuthorized(toolCall("next"), nextTool, null);

        assertThat(failed.success()).isFalse();
        assertThat(next.success()).isTrue();
        assertThat(next.status()).isNull();
    }
}

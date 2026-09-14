package com.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.ArtifactStore;
import com.harness.core.model.CancellationToken;
import com.harness.core.model.FinalOutputContract;
import com.harness.core.model.ToolSpec;
import com.harness.core.runtime.RunTrace;
import com.harness.core.runtime.RunTraceFactory;
import com.harness.react.ReActLoop;
import com.harness.react.ReActLoopFactory;
import com.harness.react.ReActRequest;
import com.harness.react.ReActResult;
import com.harness.tool.Tool;
import com.harness.tool.ToolExecutor;
import com.harness.tool.ToolRegistry;
import com.harness.tool.web.AuthorizedUrlContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubAgentManagerCompletionContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void unmetContractEndsAsIncompleteAndPassesSchemaToFinalModelCall() throws Exception {
        ReActLoopFactory loopFactory = mock(ReActLoopFactory.class);
        ReActLoop loop = mock(ReActLoop.class);
        when(loopFactory.create(any(), any())).thenReturn(loop);
        when(loop.execute(any())).thenReturn(new ReActResult(
                "{\"summary\":\"partial\"}", List.of(), List.of()));
        RunTrace trace = mock(RunTrace.class);
        RunTraceFactory traceFactory = () -> trace;
        ArtifactStore artifactStore = mock(ArtifactStore.class);
        when(artifactStore.get(any())).thenReturn(Optional.empty());
        SubAgentManager manager = new SubAgentManager(
                loopFactory, traceFactory, mock(ToolExecutor.class), artifactStore,
                new SessionInbox(), mock(SessionResumeDispatcher.class),
                mock(com.harness.provider.ChatModelProvider.class));

        try {
            ToolRegistry registry = new ToolRegistry();
            registry.register(tool("report_tool"));
            var catalog = registry.snapshot();
            String runId = "run-1";
            manager.openScope(runId);
            AgentRunContext runContext = new AgentRunContext(
                    runId, "session-1", new CancellationToken(), "parent-trace", catalog);
            JsonNode schema = objectMapper.readTree("""
                    {
                      "type":"object",
                      "properties":{"summary":{"type":"string"}},
                      "required":["summary"],
                      "additionalProperties":false
                    }
                    """);
            SubAgentTask task = new SubAgentTask(
                    "task-1", "description", "context", "persona", "prompt",
                    List.of("report_tool"), List.of(),
                    new SubAgentCompletionContract(
                            java.util.Set.of("report_tool"), List.of(), schema));

            SubAgentTaskRecord record = manager.submitTask(runContext, task, "session-1");
            SubAgentResult result = record.completion().get(5, TimeUnit.SECONDS);

            assertThat(record.status().get()).isEqualTo(SubAgentStatus.INCOMPLETE);
            assertThat(result.status()).isEqualTo(SubAgentStatus.INCOMPLETE);
            assertThat(result.contractValidation().status())
                    .isEqualTo(ContractValidation.Status.FAILED_CONTRACT);
            ArgumentCaptor<ReActRequest> requestCaptor =
                    ArgumentCaptor.forClass(ReActRequest.class);
            verify(loop).execute(requestCaptor.capture());
            assertThat(requestCaptor.getValue().finalOutputContract())
                    .isInstanceOf(FinalOutputContract.JsonSchema.class);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void subAgentThreadInheritsParentAuthorizedUrls() throws Exception {
        ReActLoopFactory loopFactory = mock(ReActLoopFactory.class);
        ReActLoop loop = mock(ReActLoop.class);
        when(loopFactory.create(any(), any())).thenReturn(loop);
        AtomicBoolean subAgentSawParentUrl = new AtomicBoolean();
        when(loop.execute(any())).thenAnswer(invocation -> {
            // Runs on the sub-agent-worker thread, not the parent thread.
            AuthorizedUrlContext.requireAuthorized(
                    "https://example.com/user-given", "read_url_content");
            subAgentSawParentUrl.set(true);
            return new ReActResult("done", List.of(), List.of());
        });
        RunTrace trace = mock(RunTrace.class);
        ArtifactStore artifactStore = mock(ArtifactStore.class);
        when(artifactStore.get(any())).thenReturn(Optional.empty());
        SubAgentManager manager = new SubAgentManager(
                loopFactory, () -> trace, mock(ToolExecutor.class), artifactStore,
                new SessionInbox(), mock(SessionResumeDispatcher.class),
                mock(com.harness.provider.ChatModelProvider.class));

        AuthorizedUrlContext.setFromUserText("请读取 https://example.com/user-given");
        try {
            String runId = "run-url";
            manager.openScope(runId);
            AgentRunContext runContext = new AgentRunContext(
                    runId, "session-url", new CancellationToken(), "parent-trace",
                    new ToolRegistry().snapshot());
            SubAgentTask task = new SubAgentTask(
                    "task-url", "description", "context", "persona", "prompt",
                    List.of(), List.of(), null);

            manager.submitTask(runContext, task, "session-url")
                    .completion().get(5, TimeUnit.SECONDS);

            assertThat(subAgentSawParentUrl).isTrue();
        } finally {
            AuthorizedUrlContext.clear();
            manager.shutdown();
        }
    }

    private Tool tool(String name) {
        return new Tool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec(name, name, objectMapper.createObjectNode());
            }

            @Override
            public String execute(JsonNode arguments) {
                return "ok";
            }
        };
    }
}

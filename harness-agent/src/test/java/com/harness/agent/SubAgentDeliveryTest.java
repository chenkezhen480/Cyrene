package com.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.CancellationToken;
import com.harness.core.runtime.RunTrace;
import com.harness.provider.ChatModelProvider;
import com.harness.react.ReActLoopFactory;
import com.harness.tool.ToolGroup;
import com.harness.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SubAgentDeliveryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SessionInbox inbox = new SessionInbox();
    private final SubAgentManager manager = new SubAgentManager(
            mock(ReActLoopFactory.class), RunTrace::noop, null, mock(com.harness.core.model.ArtifactStore.class),
            inbox, mock(SessionResumeDispatcher.class), mock(ChatModelProvider.class));
    private final SubAgentRunScope scope = manager.openScope("run");
    private final ToolGroup tool = new ToolGroup("subagent", "tasks", Map.of(
            "await", new AwaitSubAgentsTool(manager), "get", new GetSubAgentsTool(manager)), List.of());

    @AfterEach
    void close() {
        SpawnSubAgentTool.clearCurrentRunContext();
        manager.shutdown();
    }

    @Test
    void anyReturnsCompletedResultsWithoutTreatingEarlyReturnAsTimeout() throws Exception {
        succeed(task("done"));
        var pending = task("pending");
        var result = call("await", """
                {"task_ids":["done","pending"],"return_when":"ANY","on_timeout":"CANCEL"}
                """);
        assertThat(result.path("wait_timed_out").asBoolean()).isFalse();
        assertThat(result.path("completed").size()).isEqualTo(1);
        assertThat(pending.status().get()).isEqualTo(SubAgentStatus.QUEUED);
    }

    @Test
    void repeatedAwaitReturnsCompletedResultsWithoutReportingTimeout() throws Exception {
        succeed(task("done"));
        call("await", "{\"task_ids\":[\"done\"]}");
        var result = call("await", "{\"task_ids\":[\"done\"]}");
        assertThat(result.path("wait_timed_out").asBoolean()).isFalse();
        assertThat(result.path("completed").get(0).path("output").asText()).isEqualTo("answer");
    }

    @Test
    void firstSuccessReturnsTerminalFailuresInsteadOfCallingThemPending() throws Exception {
        var failed = task("failed");
        failed.fail(SubAgentResult.failure("failed", "failure", 0, false));
        var result = call("await", """
                {"task_ids":["failed"],"return_when":"FIRST_SUCCESS","timeout_seconds":0}
                """);
        assertThat(result.path("wait_timed_out").asBoolean()).isFalse();
        assertThat(result.path("completed").size()).isEqualTo(1);
        assertThat(result.path("deferred").size()).isZero();
    }

    @Test
    void getConsumesTheResultThroughTheGroupedToolEntry() throws Exception {
        var record = task("done");
        succeed(record);
        var result = call("get", "{\"task_ids\":[\"done\"]}");
        assertThat(result.path("tasks").get(0).path("result").path("output").asText()).isEqualTo("answer");
        assertThat(record.deliveryState().get()).isEqualTo(ResultDeliveryState.INLINE_CONSUMED);
    }

    @Test
    void ownerFinishDeliversAnUnconsumedCompletedTaskExactlyOnce() {
        var record = task("done");
        succeed(record);
        manager.finishRun("run");
        manager.finishRun("run");
        assertThat(inbox.drain("session")).hasSize(1);
    }

    @Test
    void unconsumedTaskKeepsTheParentTurnOpenUntilResultDelivery() {
        var record = task("unconsumed");
        succeed(record);
        assertThat(manager.hasDetachedTasks("run")).isTrue();
        record.consumeInline();
        assertThat(manager.hasDetachedTasks("run")).isFalse();
    }

    @Test
    void parentCancellationDoesNotResumeTheCancelledConversation() {
        var parent = new CancellationToken();
        var record = scope.registerTask(new SubAgentTask("cancelled-parent", "task", "", "persona", "prompt",
                List.of(), List.of(), null), CancellationToken.createChild(parent), "session");
        parent.cancel();
        record.markCancelled();
        manager.finishRun("run");
        assertThat(inbox.hasPending("session")).isFalse();
    }

    @Test
    void queuedCancellationCompletesWithoutWaitingForAWorker() {
        var record = task("queued");
        record.requestCancel();
        assertThat(record.completion()).isCompleted();
        assertThat(record.storedResult().status()).isEqualTo(SubAgentStatus.CANCELLED);
        assertThat(record.start()).isFalse();
    }

    @Test
    void timeoutResultKeepsItsTerminalStatus() {
        var record = task("timeout");
        record.markTimedOut();
        assertThat(record.completion().join().status()).isEqualTo(SubAgentStatus.TIMED_OUT);
    }

    @Test
    void detachingBeforeCompletionDeliversExactlyOnce() {
        var record = task("detached");
        manager.detachTask(record);
        succeed(record);
        assertThat(inbox.hasPending("session")).isTrue();
        manager.detachTask(record);
        assertThat(inbox.drain("session")).hasSize(1);
    }

    @Test
    void detachingAfterCompletionDeliversExactlyOnce() {
        var record = task("completed");
        succeed(record);
        manager.detachTask(record);
        manager.detachTask(record);
        assertThat(inbox.drain("session")).hasSize(1);
    }

    @Test
    void cancellationWinsOverLateWorkerFailure() {
        var record = task("cancelled");
        record.start();
        record.requestCancel();
        record.fail(SubAgentResult.failure(record.taskId(), "interrupted", 0, false));
        assertThat(record.completion().join().status()).isEqualTo(SubAgentStatus.CANCELLED);
    }

    @Test
    void awaitPropagatesArtifactsToTheParentToolOutput() throws Exception {
        var record = task("artifact");
        var artifact = new com.harness.core.model.Artifact("artifact-1", "session", "report.txt",
                com.harness.core.model.Artifact.ArtifactType.DOCUMENT, "text/plain", 10, "report.txt",
                java.time.Instant.EPOCH);
        record.start();
        record.succeed(SubAgentResult.success(record.taskId(), "answer",
                new SubAgentCompletionContractValidator.Evaluation(List.of(artifact), ToolExecutionSummary.empty(),
                        ContractValidation.notDeclared(), null), 0, null));
        SpawnSubAgentTool.setCurrentRunContext(new AgentRunContext(
                "run", "session", new CancellationToken(), "trace", new ToolRegistry().snapshot()));
        var outcome = tool.executeOutcome(mapper.readTree("""
                {"action":"await","input":{"task_ids":["artifact"]}}
                """));
        assertThat(outcome.content().artifacts()).containsExactly(artifact);
    }

    @Test
    void getDoesNotAttachArtifactsFromATaskReportedAsPending() throws Exception {
        var artifact = new com.harness.core.model.Artifact("late-artifact", "session", "late.txt",
                com.harness.core.model.Artifact.ArtifactType.DOCUMENT, "text/plain", 1, "late.txt",
                java.time.Instant.EPOCH);
        var record = org.mockito.Mockito.spy(task("late"));
        // Complete exactly after the get action takes its pending/terminal snapshot.
        org.mockito.Mockito.doAnswer(invocation -> {
            var wasTerminal = invocation.callRealMethod();
            org.mockito.Mockito.doCallRealMethod().when(record).isTerminal();
            record.start();
            record.succeed(SubAgentResult.success(record.taskId(), "late result",
                    new SubAgentCompletionContractValidator.Evaluation(List.of(artifact), ToolExecutionSummary.empty(),
                            ContractValidation.notDeclared(), null), 0, null));
            return wasTerminal;
        }).when(record).isTerminal();
        var scopeView = org.mockito.Mockito.spy(scope);
        org.mockito.Mockito.doReturn(record).when(scopeView).getTask("late");
        var managerView = mock(SubAgentManager.class);
        org.mockito.Mockito.when(managerView.getScope("run")).thenReturn(scopeView);
        SpawnSubAgentTool.setCurrentRunContext(new AgentRunContext(
                "run", "session", new CancellationToken(), "trace", new ToolRegistry().snapshot()));
        var output = new GetSubAgentsTool(managerView)
                .executeOutcome(mapper.readTree("{\"task_ids\":[\"late\"]}")).content();
        assertThat(mapper.readTree(output.text()).path("tasks").get(0).has("result")).isFalse();
        assertThat(output.artifacts()).isEmpty();
    }

    @Test
    void completionDuringDetachIsDeliveredOnlyBySessionResume() throws Exception {
        var record = task("detaching");
        var managerView = org.mockito.Mockito.spy(manager);
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.callRealMethod();
            succeed(record);
            return null;
        }).when(managerView).detachTask(record);
        SpawnSubAgentTool.setCurrentRunContext(new AgentRunContext(
                "run", "session", new CancellationToken(), "trace", new ToolRegistry().snapshot()));
        var output = new AwaitSubAgentsTool(managerView).executeOutcome(mapper.readTree("""
                {"task_ids":["detaching"],"timeout_seconds":0,"on_timeout":"RESUME_SESSION"}
                """)).content();
        var response = mapper.readTree(output.text());
        assertThat(response.path("completed").size()).isZero();
        assertThat(response.path("deferred").size()).isEqualTo(1);
        assertThat(output.artifacts()).isEmpty();
        assertThat(inbox.drain("session")).hasSize(1);
    }

    private SubAgentTaskRecord task(String id) {
        return scope.registerTask(new SubAgentTask(id, "task", "", "persona", "prompt",
                List.of(), List.of(), null), new CancellationToken(), "session");
    }

    private void succeed(SubAgentTaskRecord record) {
        record.start();
        record.succeed(SubAgentResult.success(record.taskId(), "answer",
                new SubAgentCompletionContractValidator.Evaluation(List.of(), ToolExecutionSummary.empty(),
                        ContractValidation.notDeclared(), null), 0, null));
    }

    private JsonNode call(String action, String input) throws Exception {
        SpawnSubAgentTool.setCurrentRunContext(new AgentRunContext(
                "run", "session", new CancellationToken(), "trace", new ToolRegistry().snapshot()));
        var arguments = mapper.createObjectNode().put("action", action);
        arguments.set("input", mapper.readTree(input));
        return mapper.readTree(tool.execute(arguments));
    }
}

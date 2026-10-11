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
    private final AgentRunContext.Owner owner = new AgentRunContext.Owner("user", "tenant", "DEFAULT");
    private final com.harness.agent.subagent.SubAgentTaskRepository repository =
            new com.harness.agent.subagent.InMemorySubAgentTaskRepository(java.time.Duration.ofHours(1));
    private final SubAgentManager manager = new SubAgentManager(
            mock(ReActLoopFactory.class), RunTrace::noop, null, mock(com.harness.core.model.ArtifactStore.class),
            inbox, mock(SessionResumeDispatcher.class), mock(ChatModelProvider.class), repository);
    private final SubAgentRunScope scope = manager.openScope("run");
    private final ToolGroup tool = new ToolGroup("subagent", "tasks", Map.of(
            "get", new GetSubAgentsTool(manager)), List.of());

    @AfterEach
    void close() {
        SpawnSubAgentTool.clearCurrentRunContext();
        manager.shutdown();
    }

    @Test
    void batchWaitDoesNotReturnUntilBothSubmittedChildrenComplete() throws Exception {
        var first = task("first");
        var second = task("second");
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var waited = worker.submit(() -> coordinate(manager, List.of(first, second),
                    java.time.Duration.ofSeconds(5), true));
            succeed(first);
            assertThat(waited.isDone()).isFalse();
            succeed(second);
            var results = waited.get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(results).allMatch(result -> result.resultStatus() == com.harness.core.model.ResultStatus.AVAILABLE);
            assertThat(results.stream().map(com.harness.core.model.ToolResult::toolCallId))
                    .containsExactly("call-first", "call-second");
            assertThat(inbox.hasPending("session")).isFalse();
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void sharedDeadlineReturnsCompletedResultsAndDetachesOnlyPending() throws Exception {
        var done = task("done");
        succeed(done);
        var pending = task("pending");
        var results = coordinate(manager, List.of(done, pending), java.time.Duration.ZERO, true);
        assertThat(mapper.readTree(results.getFirst().output()).path("output").asText()).isEqualTo("answer");
        assertThat(results.getLast().resultStatus()).isEqualTo(com.harness.core.model.ResultStatus.PENDING);
        assertThat(pending.deliveryState().get()).isEqualTo(ResultDeliveryState.DETACHED);
        assertThat(pending.status().get()).isEqualTo(SubAgentStatus.QUEUED);
    }

    @Test
    void disabledAutoWaitDetachesEvenAlreadyCompletedResults() {
        var done = task("done");
        succeed(done);
        var result = coordinate(manager, List.of(done), java.time.Duration.ofSeconds(1), false).getFirst();
        assertThat(result.resultStatus()).isEqualTo(com.harness.core.model.ResultStatus.PENDING);
        assertThat(inbox.drain("session")).isEmpty();
        manager.finishRun("run");
        assertThat(inbox.drain("session")).hasSize(1);
    }

    @Test
    void terminalFailuresAreReturnedInsteadOfCallingThemPending() throws Exception {
        var failed = task("failed");
        failed.fail(SubAgentResult.failure("failed", "failure", 0, false));
        var result = coordinate(manager, List.of(failed), java.time.Duration.ZERO, true).getFirst();
        assertThat(result.resultStatus()).isEqualTo(com.harness.core.model.ResultStatus.AVAILABLE);
        assertThat(mapper.readTree(result.output()).path("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void getReadsWithoutConsumingTheResultThroughTheGroupedToolEntry() throws Exception {
        var record = task("done");
        succeed(record);
        var result = call("get", "{\"task_ids\":[\"done\"]}");
        assertThat(result.path("items").get(0).path("result").path("output").asText()).isEqualTo("answer");
        assertThat(record.deliveryState().get()).isEqualTo(ResultDeliveryState.INLINE_PENDING);
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
        assertThat(inbox.hasPending("session")).isFalse();
        manager.finishRun("run");
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
        manager.finishRun("run");
        manager.finishRun("run");
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
    void coordinationPropagatesArtifactsToTheParentToolOutput() throws Exception {
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
        var result = coordinate(manager, List.of(record), java.time.Duration.ZERO, true).getFirst();
        assertThat(result.content().artifacts()).containsExactly(artifact);
    }

    @Test
    void getDoesNotAttachArtifactsFromATaskReportedAsPending() throws Exception {
        var record = task("late");
        var snapshot = repository.findAuthorized(owner, "session", "late").orElseThrow();
        var managerView = mock(SubAgentManager.class);
        org.mockito.Mockito.when(managerView.findTasks(owner, "session", List.of("late")))
                .thenReturn(List.of(snapshot));
        succeed(record);
        SpawnSubAgentTool.setCurrentRunContext(new AgentRunContext(
                "run", "session", new CancellationToken(), "trace", new ToolRegistry().snapshot(), "turn", owner));
        var output = new GetSubAgentsTool(managerView)
                .executeOutcome(mapper.readTree("{\"task_ids\":[\"late\"]}")).content();
        assertThat(mapper.readTree(output.text()).path("items").get(0).path("result").isNull()).isTrue();
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
        var output = coordinate(managerView, List.of(record), java.time.Duration.ZERO, true).getFirst().content();
        var response = mapper.readTree(output.text());
        assertThat(response.path("status").asText()).isEqualTo("PENDING");
        assertThat(output.artifacts()).isEmpty();
        manager.finishRun("run");
        assertThat(inbox.drain("session")).hasSize(1);
    }

    private SubAgentTaskRecord task(String id) {
        return scope.registerTask(new SubAgentTask(id, "task", "", "persona", "prompt",
                List.of(), List.of(), null), new CancellationToken(), "session", "turn", owner,
                "call-" + id, "trace", repository);
    }

    private List<com.harness.core.model.ToolResult> coordinate(SubAgentManager delegate,
            List<SubAgentTaskRecord> records, java.time.Duration timeout, boolean autoWait) {
        var view = org.mockito.Mockito.mockingDetails(delegate).isSpy()
                ? delegate : org.mockito.Mockito.spy(delegate);
        var calls = new java.util.ArrayList<com.harness.core.model.ToolCall>();
        var results = new java.util.ArrayList<com.harness.core.model.ToolResult>();
        for (var record : records) {
            String callId = "call-" + record.taskId();
            org.mockito.Mockito.doReturn(record).when(view).findTask("run", callId);
            calls.add(new com.harness.core.model.ToolCall(callId, "subagent", mapper.createObjectNode()));
            results.add(com.harness.core.model.ToolResult.ok(callId, "subagent", "accepted", 0));
        }
        return new SubAgentBatchCoordinator(view, "run", timeout, autoWait)
                .coordinate(calls, results, new CancellationToken());
    }

    private void succeed(SubAgentTaskRecord record) {
        record.start();
        record.succeed(SubAgentResult.success(record.taskId(), "answer",
                new SubAgentCompletionContractValidator.Evaluation(List.of(), ToolExecutionSummary.empty(),
                        ContractValidation.notDeclared(), null), 0, null));
    }

    private JsonNode call(String action, String input) throws Exception {
        SpawnSubAgentTool.setCurrentRunContext(new AgentRunContext(
                "run", "session", new CancellationToken(), "trace", new ToolRegistry().snapshot(), "turn", owner));
        var arguments = mapper.createObjectNode().put("action", action);
        arguments.set("input", mapper.readTree(input));
        return mapper.readTree(tool.execute(arguments));
    }
}

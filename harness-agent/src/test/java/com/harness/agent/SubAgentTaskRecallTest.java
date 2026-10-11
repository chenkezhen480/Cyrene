package com.harness.agent;

import com.harness.agent.subagent.InMemorySubAgentTaskRepository;
import com.harness.core.model.CancellationToken;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;

class SubAgentTaskRecallTest {
    private final InMemorySubAgentTaskRepository repository = new InMemorySubAgentTaskRepository(Duration.ofDays(1));
    private final AgentRunContext.Owner owner = new AgentRunContext.Owner("user", "tenant", "business");

    @Test void recallSurvivesScopeRemovalAndNeverConsumesDelivery() {
        SubAgentTaskRecord record = task("sub-1");
        record.succeed(result(record));
        assertThat(repository.findAuthorized(owner, "session", record.taskId()).orElseThrow().result().output()).isEqualTo("done");
        assertThat(repository.findAuthorized(owner, "session", record.taskId()).orElseThrow().deliveryState()).isEqualTo(ResultDeliveryState.INLINE_PENDING);
        assertThat(repository.findAuthorized(new AgentRunContext.Owner("other", "tenant", "business"), "session", record.taskId())).isEmpty();
        assertThat(repository.findAuthorized(new AgentRunContext.Owner("user", "other", "business"), "session", record.taskId())).isEmpty();
        assertThat(repository.findAuthorized(owner, "other", record.taskId())).isEmpty();
    }

    @Test void inlineAndDetachedHaveOneWinnerAndStableRecoverableEvent() {
        SubAgentTaskRecord record = task("sub-2");
        record.succeed(result(record));
        CompletableFuture<Boolean> inline = CompletableFuture.supplyAsync(record::consumeInline);
        CompletableFuture<Boolean> detached = CompletableFuture.supplyAsync(record::detach);
        assertThat(List.of(inline.join(), detached.join())).containsExactlyInAnyOrder(true, false);
        if (record.isDetached()) {
            var first = repository.claimDeliveries("session", Duration.ofSeconds(30), 50);
            assertThat(first).hasSize(1);
            assertThat(repository.claimDeliveries("session", Duration.ofSeconds(30), 50)).isEmpty();
            repository.releaseDelivery(first.getFirst().eventId(), first.getFirst().leaseToken());
            var retry = repository.claimDeliveries("session", Duration.ofSeconds(30), 50);
            assertThat(retry.getFirst().eventId()).isEqualTo(first.getFirst().eventId());
            repository.acknowledgeDelivery(retry.getFirst().eventId(), retry.getFirst().leaseToken());
            assertThat(repository.claimDeliveries("session", Duration.ofSeconds(30), 50)).isEmpty();
        }
    }

    @Test void restartInterruptsUnfinishedTasksWithoutReplayAndPagesByStableCursor() {
        SubAgentTaskRecord first = task("sub-a");
        SubAgentTaskRecord second = task("sub-b");
        second.start();
        repository.interruptUnfinished(50);
        var page = repository.listAuthorized(owner, "session", "", 1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.pageInfo().hasMore()).isTrue();
        assertThat(repository.listAuthorized(owner, "session", page.pageInfo().nextCursor(), 1).items()).hasSize(1);
        assertThat(repository.findAuthorized(owner, "session", first.taskId()).orElseThrow().status()).isEqualTo(SubAgentStatus.INTERRUPTED);
        assertThat(repository.findAuthorized(owner, "session", second.taskId()).orElseThrow().result().error()).contains("interrupted");
    }

    private SubAgentTaskRecord task(String taskId) {
        var task = SubAgentTask.create(taskId, "task", null, null, null, List.of(), List.of(), null);
        var record = new SubAgentTaskRecord(taskId, "run", "session", "turn", task,
                new CancellationToken(), owner, "spawn-" + taskId, "root-trace", repository);
        repository.create(record);
        return record;
    }

    private SubAgentResult result(SubAgentTaskRecord record) {
        return new SubAgentResult(record.taskId(), "done", null, true, SubAgentStatus.SUCCEEDED,
                List.of(), ToolExecutionSummary.empty(), ContractValidation.notDeclared(), null, 1, "trace");
    }
}

package com.harness.agent;

import com.harness.agent.subagent.InMemorySubAgentTaskRepository;
import com.harness.core.model.CancellationToken;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

class SessionResumeDispatcherTest {
    final InMemorySubAgentTaskRepository repository = new InMemorySubAgentTaskRepository(Duration.ofDays(1));
    final AgentRunContext.Owner owner = new AgentRunContext.Owner("user", "tenant", "business");

    @Test void queuedResumeDefersUntilForegroundFinishesWithoutClaimingItsResult() throws Exception {
        completed("blocker"); completed("session"); completed("marker");
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var marker = new CountDownLatch(1);
        var resumed = new CountDownLatch(1);
        var dispatcher = new SessionResumeDispatcher(new SessionInbox(repository, Duration.ofMinutes(1)), (session, events) -> {
            if (session.equals("blocker")) { blocked.countDown(); await(release); }
            else if (session.equals("marker")) marker.countDown();
            else resumed.countDown();
        });
        SessionResumeDispatcher.SessionRunLease foreground = null;
        try {
            dispatcher.requestResume("blocker");
            assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();
            dispatcher.requestResume("session");
            foreground = dispatcher.acquireForeground("session", new CancellationToken());
            dispatcher.requestResume("marker");
            release.countDown();
            assertThat(marker.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(resumed.getCount()).isOne();
            assertThat(repository.findAuthorized(owner, "session", "session").orElseThrow().deliveryState())
                    .isEqualTo(ResultDeliveryState.DETACHED);
            foreground.close();
            assertThat(resumed.await(5, TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); if (foreground != null) foreground.close(); dispatcher.shutdown(); }
        assertThat(repository.findAuthorized(owner, "session", "session").orElseThrow().deliveryState())
                .isEqualTo(ResultDeliveryState.SESSION_RESUMED);
    }

    @Test void foregroundWaitsForActiveResumeAndCancellationLeavesItsGateHeld() throws Exception {
        completed("session");
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var attempted = new CountDownLatch(1);
        var token = new CancellationToken();
        var dispatcher = new SessionResumeDispatcher(new SessionInbox(repository, Duration.ofMinutes(1)), (session, events) -> {
            started.countDown(); await(release);
        });
        try (var worker = Executors.newSingleThreadExecutor()) {
            dispatcher.requestResume("session");
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            var future = worker.submit(() -> {
                attempted.countDown();
                try (var ignored = dispatcher.acquireForeground("session", token)) { return true; }
            });
            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(future).isNotDone();
            token.cancel();
            assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(CancellationException.class);
            // Other sessions remain independent while this session's resume is active.
            try (var ignored = dispatcher.acquireForeground("other", new CancellationToken())) { }
            var next = worker.submit(() -> {
                try (var ignored = dispatcher.acquireForeground("session", new CancellationToken())) { return true; }
            });
            assertThat(next).isNotDone();
            release.countDown();
            assertThat(next.get(5, TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); dispatcher.shutdown(); }
    }

    private void completed(String session) {
        var task = SubAgentTask.create(session, "task", null, null, null, List.of(), List.of(), null);
        var record = new SubAgentTaskRecord(session, "old-run", session, "turn", task, new CancellationToken(),
                owner, "call", "trace", repository);
        repository.create(record);
        record.detach();
        record.succeed(new SubAgentResult(session, "done", null, true, SubAgentStatus.SUCCEEDED,
                List.of(), ToolExecutionSummary.empty(), ContractValidation.notDeclared(), null, 0, "trace"));
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test barrier timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}

package com.harness.agent.memory;

import com.harness.core.knowledge.KnowledgeIndexOperation;
import com.harness.core.knowledge.KnowledgeIndexTask;
import com.harness.core.knowledge.KnowledgeIndexTaskStatus;
import com.harness.tool.knowledge.authority.KnowledgeIndexOutboxStore;
import com.harness.tool.knowledge.index.KnowledgeIndexProjector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class KnowledgeIndexOutboxWorkerTest {

    private static final Instant NOW = Instant.parse("2026-09-02T02:00:00Z");

    @Test
    void successfulProjectionCompletesClaimedTask() {
        KnowledgeIndexOutboxStore store = mock(KnowledgeIndexOutboxStore.class);
        KnowledgeIndexProjector projector = mock(KnowledgeIndexProjector.class);
        KnowledgeIndexOutboxWorker worker = worker(store, projector, 5);
        KnowledgeIndexTask task = task(1);

        worker.processClaimedTask(task);

        verify(projector).project(task);
        verify(store).markCompleted(task.id(), NOW);
        verify(store, never()).reschedule(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void failedProjectionUsesExponentialBackoffBeforeAttemptLimit() {
        KnowledgeIndexOutboxStore store = mock(KnowledgeIndexOutboxStore.class);
        KnowledgeIndexProjector projector = mock(KnowledgeIndexProjector.class);
        KnowledgeIndexOutboxWorker worker = worker(store, projector, 5);
        KnowledgeIndexTask task = task(2);
        doThrow(new IllegalStateException("milvus unavailable"))
                .when(projector).project(task);

        worker.processClaimedTask(task);

        ArgumentCaptor<Instant> availableAt = ArgumentCaptor.forClass(Instant.class);
        verify(store).reschedule(
                org.mockito.ArgumentMatchers.eq(task.id()),
                availableAt.capture(),
                org.mockito.ArgumentMatchers.eq("milvus unavailable"));
        assertThat(availableAt.getValue()).isEqualTo(NOW.plus(Duration.ofMinutes(2)));
        verify(store, never()).markFailed(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void failedProjectionBecomesTerminalAtAttemptLimit() {
        KnowledgeIndexOutboxStore store = mock(KnowledgeIndexOutboxStore.class);
        KnowledgeIndexProjector projector = mock(KnowledgeIndexProjector.class);
        KnowledgeIndexOutboxWorker worker = worker(store, projector, 5);
        KnowledgeIndexTask task = task(5);
        doThrow(new IllegalStateException("schema mismatch"))
                .when(projector).project(task);

        worker.processClaimedTask(task);

        verify(store).markFailed(task.id(), NOW, "schema mismatch");
        verify(store, never()).reschedule(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void retryDelayIsBoundedAtOneHour() {
        assertThat(KnowledgeIndexOutboxWorker.retryDelay(1))
                .isEqualTo(Duration.ofMinutes(1));
        assertThat(KnowledgeIndexOutboxWorker.retryDelay(2))
                .isEqualTo(Duration.ofMinutes(2));
        assertThat(KnowledgeIndexOutboxWorker.retryDelay(100))
                .isEqualTo(Duration.ofHours(1));
    }

    @Test
    void pendingConfigurationDoesNotClaimTasks() throws InterruptedException {
        KnowledgeIndexOutboxStore store = mock(KnowledgeIndexOutboxStore.class);
        KnowledgeIndexProjector projector = mock(KnowledgeIndexProjector.class);
        var checked = new java.util.concurrent.CountDownLatch(1);
        KnowledgeIndexOutboxWorker worker = new KnowledgeIndexOutboxWorker(store, projector,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new KnowledgeIndexOutboxSettings(10, 1, Duration.ofSeconds(5), Duration.ofMinutes(30), 5),
                () -> { checked.countDown(); return false; });
        try {
            worker.start();
            assertThat(checked.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            worker.stop();
        }
        verify(store, never()).claimNext(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verifyNoInteractions(projector);
    }

    private static KnowledgeIndexOutboxWorker worker(
            KnowledgeIndexOutboxStore store,
            KnowledgeIndexProjector projector,
            int maxAttempts
    ) {
        return new KnowledgeIndexOutboxWorker(
                store,
                projector,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new KnowledgeIndexOutboxSettings(
                        10,
                        2,
                        Duration.ofSeconds(5),
                        Duration.ofMinutes(30),
                        maxAttempts));
    }

    private static KnowledgeIndexTask task(int attempts) {
        return new KnowledgeIndexTask(
                1L,
                "concept-1",
                "revision-1",
                KnowledgeIndexOperation.UPSERT_CURRENT,
                KnowledgeIndexTaskStatus.IN_PROGRESS,
                attempts,
                NOW,
                NOW,
                null,
                null,
                NOW);
    }
}

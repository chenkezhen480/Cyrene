package com.harness.agent.memory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The invariant a session's two continuations must never break: one writer's appends land as a
 * block. Interleaved appends read back as an assistant tool call whose results are separated by
 * the other writer's messages, which every OpenAI-compatible provider rejects.
 */
class SessionWriteLockTest {

    @Test
    void concurrentContinuationsDoNotInterleaveTheirWrites() throws Exception {
        List<String> writes = Collections.synchronizedList(new ArrayList<>());
        CyclicBarrier bothInside = new CyclicBarrier(2);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(continuation("A", writes, bothInside));
            Future<?> second = executor.submit(continuation("B", writes, bothInside));
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }

        // Either one writer finished before the other started, or the two overlapped. Only the
        // second is a bug: it is the transcript order that breaks a tool round.
        List<String> order = List.copyOf(writes);
        boolean aFinishedFirst = order.indexOf("A2") < order.indexOf("B1");
        boolean bFinishedFirst = order.indexOf("B2") < order.indexOf("A1");
        assertThat(aFinishedFirst || bFinishedFirst)
                .as("continuation writes must not interleave, got %s", order)
                .isTrue();
    }

    private static Runnable continuation(
            String label, List<String> writes, CyclicBarrier bothInside) {
        return () -> AgentMemoryRuntime.withSessionWriteLock("session-1", () -> {
            writes.add(label + "1");
            awaitPartnerQuietly(bothInside);
            writes.add(label + "2");
        });
    }

    private static void awaitPartnerQuietly(CyclicBarrier bothInside) {
        try {
            bothInside.await(200, TimeUnit.MILLISECONDS);
        } catch (Exception expected) {
            // A correct guard leaves the partner waiting outside, so the barrier never fills.
        }
    }
}

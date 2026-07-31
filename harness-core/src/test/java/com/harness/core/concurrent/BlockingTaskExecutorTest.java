package com.harness.core.concurrent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class BlockingTaskExecutorTest {

    @Test
    void shared_runsBlockingTaskOnVirtualThread() {
        Thread thread = CompletableFuture.supplyAsync(
                Thread::currentThread, BlockingTaskExecutor.shared()).join();

        assertThat(thread.isVirtual()).isTrue();
        assertThat(thread.getName()).startsWith("harness-blocking-");
    }
}

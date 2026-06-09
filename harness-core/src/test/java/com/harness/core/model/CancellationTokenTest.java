package com.harness.core.model;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class CancellationTokenTest {

    @Test
    void isCancelled_returnsFalse_byDefault() {
        var token = new CancellationToken();
        assertThat(token.isCancelled()).isFalse();
    }

    @Test
    void cancel_setsCancelledToTrue() {
        var token = new CancellationToken();
        token.cancel();
        assertThat(token.isCancelled()).isTrue();
    }

    @Test
    void trackCurrentThread_andCancel_interruptsCurrentThread() throws Exception {
        var token = new CancellationToken();
        var interrupted = new AtomicBoolean(false);
        var latch = new CountDownLatch(1);

        Thread worker = new Thread(() -> {
            token.trackCurrentThread();
            latch.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });

        worker.start();
        latch.await(2, TimeUnit.SECONDS);
        token.cancel();
        worker.join(2_000);

        assertThat(interrupted.get()).isTrue();
    }

    @Test
    void untrackCurrentThread_preventsInterruptionAfterCancel() throws Exception {
        var token = new CancellationToken();
        var interrupted = new AtomicBoolean(false);
        var started = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);

        Thread worker = new Thread(() -> {
            token.trackCurrentThread();
            token.untrackCurrentThread();
            started.countDown();
            try {
                proceed.await(2, TimeUnit.SECONDS);
                Thread.sleep(100);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });

        worker.start();
        started.await(2, TimeUnit.SECONDS);
        token.cancel();
        proceed.countDown();
        worker.join(2_000);

        assertThat(interrupted.get()).isFalse();
    }

    @Test
    void trackThread_andCancel_interruptsArbitraryThread() throws Exception {
        var token = new CancellationToken();
        var interrupted = new AtomicBoolean(false);
        var latch = new CountDownLatch(1);

        Thread worker = new Thread(() -> {
            latch.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });

        worker.start();
        latch.await(2, TimeUnit.SECONDS);
        token.trackThread(worker);
        token.cancel();
        worker.join(2_000);

        assertThat(interrupted.get()).isTrue();
    }

    @Test
    void concurrentCancel_doesNotThrow() throws Exception {
        var token = new CancellationToken();
        var threads = new Thread[10];
        var latch = new CountDownLatch(1);

        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                try {
                    latch.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                token.cancel();
            });
            threads[i].start();
        }

        latch.countDown();
        for (Thread t : threads) {
            t.join(2_000);
        }

        assertThat(token.isCancelled()).isTrue();
    }
}

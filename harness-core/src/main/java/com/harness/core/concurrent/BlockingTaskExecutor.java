package com.harness.core.concurrent;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

/**
 * Shared virtual-thread executor for blocking framework tasks.
 */
public final class BlockingTaskExecutor {

    private static final ThreadFactory THREAD_FACTORY =
            Thread.ofVirtual().name("harness-blocking-", 0).factory();
    private static final Executor INSTANCE =
            command -> THREAD_FACTORY.newThread(command).start();

    private BlockingTaskExecutor() {
    }

    public static Executor shared() {
        return INSTANCE;
    }
}

package com.harness.provider.impl;

import java.util.function.Supplier;

/** Disables application-level Chat retries for one call on the current thread. */
public final class ChatRetryContext {
    private static final ThreadLocal<Boolean> DISABLED = new ThreadLocal<>();

    private ChatRetryContext() {}

    public static <T> T withoutRetry(Supplier<T> action) {
        Boolean previous = DISABLED.get();
        DISABLED.set(true);
        try {
            return action.get();
        } finally {
            if (previous == null) DISABLED.remove();
            else DISABLED.set(previous);
        }
    }

    static boolean retriesEnabled() {
        return !Boolean.TRUE.equals(DISABLED.get());
    }
}

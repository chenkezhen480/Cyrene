package com.harness.agent;

/** Result routing is independent of task execution; only an acknowledged durable callback resumes it. */
public enum ResultDeliveryState {
    INLINE_PENDING,
    INLINE_CONSUMED,
    DETACHED,
    DELIVERY_CLAIMED,
    SESSION_RESUMED,
    SUPPRESSED
}

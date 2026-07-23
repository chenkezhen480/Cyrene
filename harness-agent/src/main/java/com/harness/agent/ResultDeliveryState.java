package com.harness.agent;

/**
 * Tracks how a sub-agent result will be delivered to the consumer.
 * Separates task execution status from result delivery status.
 *
 * State machine:
 *   INLINE_PENDING  →  INLINE_CONSUMED   (await_subagents got result in current run)
 *   INLINE_PENDING  →  DETACHED          (await timed out, result goes to SessionInbox later)
 *   DETACHED        →  SESSION_RESUMED   (completion event submitted, will trigger resume)
 */
public enum ResultDeliveryState {
    /** Result not yet available; will be consumed inline if await succeeds before timeout. */
    INLINE_PENDING,
    /** Result was consumed by await_subagents in the current run. No session resume needed. */
    INLINE_CONSUMED,
    /** Await timed out; result will be submitted to SessionInbox when task completes. */
    DETACHED,
    /** Completion event submitted to SessionInbox. Session resume will be triggered. */
    SESSION_RESUMED
}

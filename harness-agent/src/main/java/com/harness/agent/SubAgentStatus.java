package com.harness.agent;

/**
 * Sub-agent task lifecycle status.
 */
public enum SubAgentStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCEL_REQUESTED,
    CANCELLED,
    TIMED_OUT
}

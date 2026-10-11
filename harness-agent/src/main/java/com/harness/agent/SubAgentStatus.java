package com.harness.agent;

/**
 * Sub-agent task lifecycle status.
 */
public enum SubAgentStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    INCOMPLETE,
    FAILED,
    CANCEL_REQUESTED,
    CANCELLED,
    TIMED_OUT,
    INTERRUPTED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == INCOMPLETE || this == FAILED
                || this == CANCELLED || this == TIMED_OUT || this == INTERRUPTED;
    }
}

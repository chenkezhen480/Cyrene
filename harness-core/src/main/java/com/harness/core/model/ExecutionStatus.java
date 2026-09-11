package com.harness.core.model;

/** Whether a Tool invocation itself completed without an execution boundary failure. */
public enum ExecutionStatus {
    SUCCEEDED,
    FAILED,
    CONFIRMATION_REQUIRED,
    REJECTED,
    EXPIRED,
    CANCELLED
}

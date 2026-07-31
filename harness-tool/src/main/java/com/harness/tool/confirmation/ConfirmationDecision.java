package com.harness.tool.confirmation;

/**
 * Terminal decision for a pending tool confirmation request.
 */
public enum ConfirmationDecision {
    APPROVED,
    REJECTED,
    EXPIRED,
    CANCELLED
}

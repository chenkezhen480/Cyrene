package com.harness.core.knowledge;

public enum KnowledgeIndexTaskStatus {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    FAILED("failed");

    private final String storageValue;

    KnowledgeIndexTaskStatus(String storageValue) {
        this.storageValue = storageValue;
    }

    public String storageValue() {
        return storageValue;
    }

    public boolean terminal() {
        return this == COMPLETED || this == FAILED;
    }
}

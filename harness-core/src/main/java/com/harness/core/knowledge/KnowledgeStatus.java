package com.harness.core.knowledge;

public enum KnowledgeStatus {
    DRAFT("draft"),
    STABLE("stable"),
    DEPRECATED("deprecated");

    private final String storageValue;

    KnowledgeStatus(String storageValue) {
        this.storageValue = storageValue;
    }

    public String storageValue() {
        return storageValue;
    }
}

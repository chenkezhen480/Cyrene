package com.harness.core.knowledge;

public enum KnowledgeConceptType {
    SOURCE_DOCUMENT("Source Document"),
    GRAPH_SCHEMA("Graph Schema"),
    GRAPH_SPACE("Graph Space"),
    USER_PREFERENCE("User Preference"),
    USER_EPISODE("User Episode"),
    OPERATION_PLAYBOOK("Operation Playbook");

    private final String displayName;

    KnowledgeConceptType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isUserOwned() {
        return this == USER_PREFERENCE || this == USER_EPISODE;
    }

    public boolean isMemory() {
        return isUserOwned() || this == OPERATION_PLAYBOOK;
    }
}

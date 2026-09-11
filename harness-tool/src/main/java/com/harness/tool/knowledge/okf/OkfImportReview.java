package com.harness.tool.knowledge.okf;

import com.harness.core.knowledge.OkfKnowledgeDocument;

import java.util.List;

public record OkfImportReview(List<Entry> entries) {

    public OkfImportReview {
        entries = List.copyOf(entries == null ? List.of() : entries);
    }

    public boolean committable() {
        return entries.stream().allMatch(entry -> entry.action() != Action.REJECTED);
    }

    public record Entry(
            String path,
            Action action,
            String conceptId,
            String expectedRevisionId,
            List<String> issues,
            OkfKnowledgeDocument document
    ) {
        public Entry {
            issues = List.copyOf(issues == null ? List.of() : issues);
        }
    }

    public enum Action {
        CREATE_REQUIRES_APPROVAL,
        REVISE_REQUIRES_APPROVAL,
        UNCHANGED,
        REJECTED
    }
}

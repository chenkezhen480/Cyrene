package com.harness.tool.knowledge.okf;

import java.util.List;

public record OkfImportResult(int revisionCount, List<String> conceptIds) {
    public OkfImportResult {
        if (revisionCount < 0) {
            throw new IllegalArgumentException("revisionCount must not be negative");
        }
        conceptIds = List.copyOf(conceptIds == null ? List.of() : conceptIds);
    }
}

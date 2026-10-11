package com.harness.graph.build;

public record GraphDraftPreviewItem(String viewType, String draftId, String contentHash,
                                   String changeId, String entityType, String operation,
                                   Object before, Object after) { }

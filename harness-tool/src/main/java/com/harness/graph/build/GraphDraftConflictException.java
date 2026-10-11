package com.harness.graph.build;

/** An authorized caller can recover the current reference without applying a stale draft. */
public final class GraphDraftConflictException extends IllegalStateException {
    private final String currentDraftId;
    private final String currentContentHash;

    public GraphDraftConflictException(String message, String currentDraftId, String currentContentHash) {
        super(message + "; currentDraftId=" + java.util.Objects.requireNonNull(currentDraftId)
                + "; currentContentHash=" + java.util.Objects.requireNonNull(currentContentHash));
        this.currentDraftId = java.util.Objects.requireNonNull(currentDraftId);
        this.currentContentHash = java.util.Objects.requireNonNull(currentContentHash);
    }

    public String currentDraftId() { return currentDraftId; }
    public String currentContentHash() { return currentContentHash; }
}

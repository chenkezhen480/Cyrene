package com.harness.tool.knowledge;

/** Signals that an upload is durable and will continue through the background worker. */
public final class KnowledgeIngestPendingException extends RuntimeException {

    private final String jobId;
    private final String documentId;
    private final String sourceArtifactId;
    private final String collection;

    public KnowledgeIngestPendingException(
            String jobId,
            String documentId,
            String sourceArtifactId,
            String collection,
            RuntimeException cause
    ) {
        super("Knowledge ingest is queued for retry: " + failureMessage(cause), cause);
        this.jobId = jobId;
        this.documentId = documentId;
        this.sourceArtifactId = sourceArtifactId;
        this.collection = collection;
    }

    public String jobId() {
        return jobId;
    }

    public String documentId() {
        return documentId;
    }

    public String sourceArtifactId() {
        return sourceArtifactId;
    }

    public String collection() {
        return collection;
    }

    private static String failureMessage(RuntimeException cause) {
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName() : message;
    }
}

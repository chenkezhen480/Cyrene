package com.harness.tool.knowledge.authority;

public final class KnowledgePersistenceException extends RuntimeException {

    public KnowledgePersistenceException(String message) {
        super(message);
    }

    public KnowledgePersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}

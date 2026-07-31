package com.harness.preprocess.memory;

/**
 * Signals a persistence failure on a conversation-memory critical path.
 */
public final class MemoryStoreException extends RuntimeException {

    public MemoryStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}

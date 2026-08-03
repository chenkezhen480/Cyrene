package com.harness.agent.graph;

public final class GraphSpaceAccessException extends RuntimeException {

    public GraphSpaceAccessException(String message) {
        super(message);
    }

    public GraphSpaceAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.harness.server;

/** Raised when tenant tool-permission state cannot be read or written. */
public class ToolPermissionException extends RuntimeException {

    public ToolPermissionException(String message) {
        super(message);
    }

    public ToolPermissionException(String message, Throwable cause) {
        super(message, cause);
    }
}

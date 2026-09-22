package com.harness.provider.impl;

/**
 * Raised when an OpenAI-compatible endpoint returns a tool response that cannot be
 * represented by the normalized tool-call contract.
 */
public final class MalformedToolResponseException extends RuntimeException {

    public static final String CODE = "MALFORMED_TOOL_RESPONSE";

    public MalformedToolResponseException(String detail) {
        super(CODE + ": " + detail);
    }
}

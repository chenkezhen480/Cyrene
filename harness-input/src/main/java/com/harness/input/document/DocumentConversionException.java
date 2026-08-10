package com.harness.input.document;

/**
 * Explicit failure raised when the document parser cannot produce valid Markdown.
 */
public final class DocumentConversionException extends RuntimeException {

    private final int statusCode;
    private final String errorCode;

    public DocumentConversionException(String message) {
        this(message, -1, null, null);
    }

    public DocumentConversionException(String message, Throwable cause) {
        this(message, -1, null, cause);
    }

    public DocumentConversionException(
            String message,
            int statusCode,
            String errorCode
    ) {
        this(message, statusCode, errorCode, null);
    }

    private DocumentConversionException(
            String message,
            int statusCode,
            String errorCode,
            Throwable cause
    ) {
        super(message, cause);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    public int statusCode() {
        return statusCode;
    }

    public String errorCode() {
        return errorCode;
    }
}

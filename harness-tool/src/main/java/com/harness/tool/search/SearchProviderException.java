package com.harness.tool.search;

public final class SearchProviderException extends RuntimeException {

    private final String provider;

    public SearchProviderException(String provider, String message) {
        super(message);
        this.provider = provider;
    }

    public SearchProviderException(String provider, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
    }

    public String provider() {
        return provider;
    }
}

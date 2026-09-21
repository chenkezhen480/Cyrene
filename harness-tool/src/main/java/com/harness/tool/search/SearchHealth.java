package com.harness.tool.search;

public record SearchHealth(Status status, String message) {

    public enum Status {
        AVAILABLE,
        UNAVAILABLE
    }

    public static SearchHealth available() {
        return new SearchHealth(Status.AVAILABLE, null);
    }

    public static SearchHealth unavailable(String message) {
        return new SearchHealth(Status.UNAVAILABLE, message);
    }

    public boolean isAvailable() {
        return status == Status.AVAILABLE;
    }
}

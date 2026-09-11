package com.harness.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/** Semantic availability and verification state of a successfully executed Tool result. */
public enum ResultStatus {
    AVAILABLE,
    VERIFIED,
    EMPTY,
    PARTIAL,
    PENDING,
    LOW_RELEVANCE,
    ESCALATING,
    CONTRACT_FAILED;

    /** Old persisted SUCCESS values are deliberately unverified. */
    @JsonCreator
    public static ResultStatus fromStorage(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "SUCCESS" -> AVAILABLE;
            case "CONFIRMATION_REQUIRED" -> PENDING;
            case "CONFIRMATION_REJECTED", "CONFIRMATION_EXPIRED", "CONFIRMATION_CANCELLED" ->
                    CONTRACT_FAILED;
            default -> valueOf(value.trim().toUpperCase(Locale.ROOT));
        };
    }

    @JsonValue
    public String storageValue() {
        return name();
    }
}

package com.harness.core.knowledge;

import java.time.Instant;
import java.util.Objects;

/** Stable compound cursor matching Verification storage order. */
public record KnowledgeVerificationCursor(Instant verifiedAt, long verificationId) {
    public KnowledgeVerificationCursor {
        verifiedAt = Objects.requireNonNull(verifiedAt, "verifiedAt");
        if (verificationId < 1) {
            throw new IllegalArgumentException("verificationId must be positive");
        }
    }

    public static KnowledgeVerificationCursor from(KnowledgeVerification verification) {
        Objects.requireNonNull(verification, "verification");
        if (verification.id() == null) {
            throw new IllegalArgumentException("Persisted Verification ID is required");
        }
        return new KnowledgeVerificationCursor(
                verification.verifiedAt(), verification.id());
    }

    public String encode() {
        return KnowledgeCursorSupport.encode(verifiedAt.toString()) + "." + verificationId;
    }

    public static KnowledgeVerificationCursor parse(String value) {
        try {
            String[] parts = KnowledgeCursorSupport.split(value, 2, "Knowledge Verification");
            return new KnowledgeVerificationCursor(
                    Instant.parse(KnowledgeCursorSupport.decode(parts[0])),
                    Long.parseLong(parts[1]));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid Knowledge Verification cursor", e);
        }
    }
}

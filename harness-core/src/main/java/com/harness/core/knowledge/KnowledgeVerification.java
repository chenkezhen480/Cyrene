package com.harness.core.knowledge;

import java.time.Instant;
import java.util.Objects;

public record KnowledgeVerification(
        Long id,
        String revisionId,
        String verifiedBy,
        KnowledgeVerificationType verificationType,
        KnowledgeVerificationResult result,
        String reason,
        Instant verifiedAt
) {
    public KnowledgeVerification {
        if (id != null && id < 1) {
            throw new IllegalArgumentException("id must be positive when present");
        }
        revisionId = KnowledgeModelSupport.requiredText(revisionId, "revisionId", 64);
        verifiedBy = KnowledgeModelSupport.requiredText(verifiedBy, "verifiedBy", 256);
        verificationType = Objects.requireNonNull(verificationType, "verificationType");
        result = Objects.requireNonNull(result, "result");
        reason = KnowledgeModelSupport.optionalText(reason, "reason", 1024);
        verifiedAt = Objects.requireNonNull(verifiedAt, "verifiedAt");
        if (!(verifiedBy.startsWith("human:")
                || verifiedBy.startsWith("process:")
                || verifiedBy.contains("/"))) {
            throw new IllegalArgumentException("verifiedBy must follow the OKF actor convention");
        }
    }
}

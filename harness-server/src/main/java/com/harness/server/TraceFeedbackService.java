package com.harness.server;

import com.harness.core.model.AgentTrace;
import com.harness.input.memory.SessionStore;
import com.harness.trace.store.TraceStore;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Owner-authorized feedback binding to one exact persisted Trace. */
final class TraceFeedbackService {

    private final TraceStore traceStore;
    private final SessionStore sessionStore;

    TraceFeedbackService(TraceStore traceStore, SessionStore sessionStore) {
        this.traceStore = Objects.requireNonNull(traceStore, "traceStore");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
    }

    FeedbackResult update(
            String traceId,
            String feedback,
            SessionRequestOwnerResolver.Owner owner
    ) {
        if (traceId == null || traceId.isBlank()) {
            throw new InvalidFeedbackException("traceId is required");
        }
        if (owner == null) {
            throw new InvalidFeedbackException("owner is required");
        }
        String normalizedFeedback = normalizeFeedback(feedback);
        AgentTrace trace = traceStore.findById(traceId.trim())
                .orElseThrow(() -> new TraceNotFoundException(traceId));
        if (trace.sessionId() == null || trace.sessionId().isBlank()) {
            throw new TraceOwnershipException(
                    "Trace is not bound to an owner-scoped Session");
        }
        boolean owned = sessionStore.findByIdAndOwner(
                trace.sessionId(), owner.userId(), owner.tenantId()).isPresent();
        if (!owned) {
            throw new TraceOwnershipException("Trace does not belong to the authenticated owner");
        }
        boolean updated = traceStore.updateMetadata(
                trace.traceId(), Map.of("user_feedback", normalizedFeedback));
        if (!updated) {
            throw new FeedbackWriteException("Trace disappeared before feedback was written");
        }
        return new FeedbackResult(trace.traceId(), normalizedFeedback);
    }

    private String normalizeFeedback(String feedback) {
        if (feedback == null || feedback.isBlank()) {
            throw new InvalidFeedbackException("feedback is required");
        }
        String normalized = feedback.trim().toLowerCase(Locale.ROOT);
        if (!"positive".equals(normalized) && !"negative".equals(normalized)) {
            throw new InvalidFeedbackException(
                    "feedback must be positive or negative");
        }
        return normalized;
    }

    record FeedbackResult(String traceId, String feedback) {
    }

    static final class InvalidFeedbackException extends RuntimeException {
        InvalidFeedbackException(String message) { super(message); }
    }

    static final class TraceNotFoundException extends RuntimeException {
        TraceNotFoundException(String traceId) { super("Trace not found: " + traceId); }
    }

    static final class TraceOwnershipException extends RuntimeException {
        TraceOwnershipException(String message) { super(message); }
    }

    static final class FeedbackWriteException extends RuntimeException {
        FeedbackWriteException(String message) { super(message); }
    }
}

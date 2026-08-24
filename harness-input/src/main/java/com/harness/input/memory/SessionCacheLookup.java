package com.harness.input.memory;

import com.harness.core.model.MemoryMessage;

import java.util.List;
import java.util.Objects;

/** Result of one cache lookup, including failures that require a database fallback. */
public record SessionCacheLookup(
        Outcome outcome,
        List<MemoryMessage> messages
) {

    public enum Outcome {
        HIT,
        MISS,
        ERROR
    }

    public SessionCacheLookup {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == Outcome.HIT && messages == null) {
            throw new IllegalArgumentException("Cache hit requires messages");
        }
        if (outcome != Outcome.HIT && messages != null) {
            throw new IllegalArgumentException("Cache miss or error cannot contain messages");
        }
    }

    public static SessionCacheLookup hit(List<MemoryMessage> messages) {
        return new SessionCacheLookup(Outcome.HIT, messages);
    }

    public static SessionCacheLookup miss() {
        return new SessionCacheLookup(Outcome.MISS, null);
    }

    public static SessionCacheLookup error() {
        return new SessionCacheLookup(Outcome.ERROR, null);
    }
}

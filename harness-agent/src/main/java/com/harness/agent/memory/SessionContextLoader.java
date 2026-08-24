package com.harness.agent.memory;

import com.harness.core.model.MemoryMessage;
import com.harness.core.runtime.RunTrace;
import com.harness.input.memory.MessageStore;
import com.harness.input.memory.SessionCacheLookup;
import com.harness.input.memory.SessionCacheMetrics;
import com.harness.input.memory.SessionMessageCache;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Loads request context from cache or persistence and records both observation layers. */
final class SessionContextLoader {

    private final SessionMessageCache messageCache;
    private final MessageStore messageStore;

    SessionContextLoader(SessionMessageCache messageCache, MessageStore messageStore) {
        this.messageCache = Objects.requireNonNull(messageCache, "messageCache");
        this.messageStore = messageStore;
    }

    List<MemoryMessage> load(String sessionId, String userId, RunTrace trace) {
        long startedAt = System.nanoTime();
        SessionCacheLookup lookup = messageCache.lookup(sessionId);
        messageCache.metrics().recordLookup(lookup.outcome());

        if (lookup.outcome() == SessionCacheLookup.Outcome.HIT) {
            long latencyMs = elapsedMs(startedAt);
            List<MemoryMessage> messages = lookup.messages();
            messageCache.metrics().recordLoad(SessionCacheMetrics.LoadSource.CACHE, latencyMs);
            recordTrace(trace, lookup.outcome(), true, "cache", latencyMs, messages.size(), 0);
            return messages;
        }

        if (messageStore == null) {
            return List.of();
        }

        try {
            List<MemoryMessage> messages = messageStore.loadForContext(sessionId);
            boolean refilled = messageCache.putObserved(sessionId, userId, messages);
            if (refilled) {
                messageCache.metrics().recordRefill();
            }
            long latencyMs = elapsedMs(startedAt);
            messageCache.metrics().recordLoad(SessionCacheMetrics.LoadSource.DATABASE, latencyMs);
            recordTrace(
                    trace,
                    lookup.outcome(),
                    false,
                    "database",
                    latencyMs,
                    messages.size(),
                    refilled ? 1 : 0);
            return messages;
        } catch (RuntimeException e) {
            long latencyMs = elapsedMs(startedAt);
            messageCache.metrics().recordLoad(SessionCacheMetrics.LoadSource.DATABASE, latencyMs);
            recordTrace(trace, lookup.outcome(), false, "database", latencyMs, 0, 0);
            throw e;
        }
    }

    private void recordTrace(
            RunTrace trace,
            SessionCacheLookup.Outcome lookupOutcome,
            boolean cacheHit,
            String loadSource,
            long latencyMs,
            int loadedMessageCount,
            int refillCount
    ) {
        trace.putMetadata(Map.of(
                "sessionCacheHit", String.valueOf(cacheHit),
                "sessionCacheLookupOutcome", lookupOutcome.name().toLowerCase(),
                "sessionCacheBackend", messageCache.metrics().backend(),
                "contextLoadSource", loadSource,
                "contextLoadLatencyMs", String.valueOf(latencyMs),
                "loadedMessageCount", String.valueOf(loadedMessageCount),
                "cacheRefillCount", String.valueOf(refillCount)));
    }

    private static long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}

package com.harness.input.memory;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * Process-local cache metrics with bounded cardinality.
 *
 * <p>This collector is independent of a particular exporter. A future
 * Micrometer or OpenTelemetry adapter can publish the immutable snapshot.</p>
 */
public final class SessionCacheMetrics {

    private static final SessionCacheMetrics NOOP = new SessionCacheMetrics("unknown", false);

    public enum LoadSource {
        CACHE,
        DATABASE
    }

    public enum EvictionReason {
        TTL,
        USER_COUNT,
        USER_BYTES,
        GLOBAL_BYTES,
        EXPLICIT
    }

    private final String backend;
    private final boolean enabled;
    private final EnumMap<SessionCacheLookup.Outcome, LongAdder> lookups =
            new EnumMap<>(SessionCacheLookup.Outcome.class);
    private final EnumMap<LoadSource, LatencyAccumulator> loadLatencies =
            new EnumMap<>(LoadSource.class);
    private final EnumMap<EvictionReason, LongAdder> evictions =
            new EnumMap<>(EvictionReason.class);
    private final LongAdder refills = new LongAdder();

    public SessionCacheMetrics(String backend) {
        this(backend, true);
    }

    private SessionCacheMetrics(String backend, boolean enabled) {
        this.backend = backend;
        this.enabled = enabled;
        for (SessionCacheLookup.Outcome outcome : SessionCacheLookup.Outcome.values()) {
            lookups.put(outcome, new LongAdder());
        }
        for (LoadSource source : LoadSource.values()) {
            loadLatencies.put(source, new LatencyAccumulator());
        }
        for (EvictionReason reason : EvictionReason.values()) {
            evictions.put(reason, new LongAdder());
        }
    }

    public static SessionCacheMetrics noop() {
        return NOOP;
    }

    public String backend() {
        return backend;
    }

    public void recordLookup(SessionCacheLookup.Outcome outcome) {
        if (enabled) {
            lookups.get(outcome).increment();
        }
    }

    public void recordLoad(LoadSource source, long latencyMs) {
        if (enabled) {
            loadLatencies.get(source).record(latencyMs);
        }
    }

    public void recordRefill() {
        if (enabled) {
            refills.increment();
        }
    }

    public void recordEviction(EvictionReason reason) {
        if (enabled) {
            evictions.get(reason).increment();
        }
    }

    public Snapshot snapshot(int activeSessions, long estimatedBytes) {
        EnumMap<SessionCacheLookup.Outcome, Long> lookupTotals =
                new EnumMap<>(SessionCacheLookup.Outcome.class);
        lookups.forEach((key, value) -> lookupTotals.put(key, value.sum()));

        EnumMap<LoadSource, LatencySnapshot> latencySnapshots =
                new EnumMap<>(LoadSource.class);
        loadLatencies.forEach((key, value) -> latencySnapshots.put(key, value.snapshot()));

        EnumMap<EvictionReason, Long> evictionTotals =
                new EnumMap<>(EvictionReason.class);
        evictions.forEach((key, value) -> evictionTotals.put(key, value.sum()));

        return new Snapshot(
                backend,
                Map.copyOf(lookupTotals),
                Map.copyOf(latencySnapshots),
                refills.sum(),
                Map.copyOf(evictionTotals),
                activeSessions,
                estimatedBytes);
    }

    public record LatencySnapshot(long count, long totalMs, long maxMs) {
        public double averageMs() {
            return count > 0 ? (double) totalMs / count : 0;
        }
    }

    public record Snapshot(
            String backend,
            Map<SessionCacheLookup.Outcome, Long> lookupTotals,
            Map<LoadSource, LatencySnapshot> loadLatencies,
            long refillTotal,
            Map<EvictionReason, Long> evictionTotals,
            int activeSessions,
            long estimatedBytes
    ) {
    }

    private static final class LatencyAccumulator {
        private final LongAdder count = new LongAdder();
        private final LongAdder totalMs = new LongAdder();
        private final LongAccumulator maxMs = new LongAccumulator(Long::max, 0);

        void record(long latencyMs) {
            long nonNegativeLatency = Math.max(latencyMs, 0);
            count.increment();
            totalMs.add(nonNegativeLatency);
            maxMs.accumulate(nonNegativeLatency);
        }

        LatencySnapshot snapshot() {
            return new LatencySnapshot(count.sum(), totalMs.sum(), maxMs.get());
        }
    }
}

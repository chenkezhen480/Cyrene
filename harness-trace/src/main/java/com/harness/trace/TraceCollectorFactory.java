package com.harness.trace;

import com.harness.trace.store.TraceStore;
import com.harness.core.runtime.RunTrace;
import com.harness.core.runtime.RunTraceFactory;

import java.util.Objects;

/** Default audit-backed trace factory. */
public final class TraceCollectorFactory implements RunTraceFactory {

    private final TraceStore traceStore;

    public TraceCollectorFactory(TraceStore traceStore) {
        this.traceStore = Objects.requireNonNull(traceStore, "traceStore");
    }

    @Override
    public RunTrace start() {
        return new TraceCollector(traceStore);
    }
}

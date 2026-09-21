package com.harness.provider.impl;

import com.harness.provider.RealtimeModelProvider;
import com.harness.provider.RealtimeCapabilities;
import com.harness.provider.RealtimeEventListener;
import com.harness.provider.RealtimeSession;
import com.harness.provider.RealtimeSessionConfig;

public class NoOpRealtimeModelProvider implements RealtimeModelProvider {
    @Override public RealtimeCapabilities capabilities() {
        return RealtimeCapabilities.none();
    }
    @Override public RealtimeSession open(
            RealtimeSessionConfig config, RealtimeEventListener listener) {
        throw new UnsupportedOperationException("Realtime model not configured.");
    }
    @Override public String providerName() { return "none"; }
}

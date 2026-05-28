package com.harness.ai.model.impl;

import com.harness.ai.model.RealtimeModelProvider;

public class NoOpRealtimeModelProvider implements RealtimeModelProvider {
    @Override public String startSession(RealtimeEventHandler handler) {
        throw new UnsupportedOperationException("Realtime model not configured.");
    }
    @Override public void send(String sessionId, byte[] data) {}
    @Override public void endSession(String sessionId) {}
    @Override public String providerName() { return "none"; }
}

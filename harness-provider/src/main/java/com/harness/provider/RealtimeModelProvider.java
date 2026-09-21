package com.harness.provider;

/** Provider-neutral entry point for a bidirectional realtime model session. */
public interface RealtimeModelProvider {

    RealtimeCapabilities capabilities();

    RealtimeSession open(RealtimeSessionConfig config, RealtimeEventListener listener);

    String providerName();

    default boolean isAvailable() {
        return !"none".equalsIgnoreCase(providerName());
    }
}

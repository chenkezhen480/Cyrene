package com.harness.provider;

@FunctionalInterface
public interface RealtimeEventListener {
    void onEvent(RealtimeEvent event);
}

package com.harness.provider;

/** One provider connection. Implementations must make close idempotent. */
public interface RealtimeSession extends AutoCloseable {

    String sessionId();

    void send(RealtimeInput input);

    void update(RealtimeSessionUpdate update);

    void sendToolResult(RealtimeToolResult result);

    void interrupt();

    RealtimeSessionState state();

    @Override
    void close();
}

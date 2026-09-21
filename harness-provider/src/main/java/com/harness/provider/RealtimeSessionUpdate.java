package com.harness.provider;

/** Mutable session fields shared by realtime providers. Null means unchanged. */
public record RealtimeSessionUpdate(
        String instructions,
        String voice,
        Boolean audioOutput,
        RealtimeSessionConfig.TurnDetection turnDetection
) {
}

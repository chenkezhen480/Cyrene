package com.harness.provider;

import com.harness.core.model.ToolSpec;

import java.util.List;

/** Provider-neutral configuration fixed when a realtime session opens. */
public record RealtimeSessionConfig(
        String instructions,
        String voice,
        boolean audioOutput,
        int inputSampleRate,
        int outputSampleRate,
        TurnDetection turnDetection,
        List<ToolSpec> tools
) {
    public enum TurnDetection { SERVER_VAD, SEMANTIC_VAD, MANUAL }

    public RealtimeSessionConfig {
        if (inputSampleRate <= 0 || outputSampleRate <= 0) {
            throw new IllegalArgumentException("audio sample rates must be positive");
        }
        turnDetection = turnDetection == null ? TurnDetection.SERVER_VAD : turnDetection;
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public static RealtimeSessionConfig defaults() {
        return new RealtimeSessionConfig(null, null, true, 16_000, 24_000,
                TurnDetection.SERVER_VAD, List.of());
    }

    public RealtimeSessionConfig withTools(List<ToolSpec> visibleTools) {
        return new RealtimeSessionConfig(instructions, voice, audioOutput,
                inputSampleRate, outputSampleRate, turnDetection, visibleTools);
    }
}

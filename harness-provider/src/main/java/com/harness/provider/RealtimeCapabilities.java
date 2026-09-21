package com.harness.provider;

/** Modalities and runtime features one realtime provider actually supports. */
public record RealtimeCapabilities(
        boolean textInput,
        boolean audioInput,
        boolean imageInput,
        boolean textOutput,
        boolean audioOutput,
        boolean toolCalling,
        boolean interruption
) {
    public static RealtimeCapabilities none() {
        return new RealtimeCapabilities(false, false, false, false, false, false, false);
    }
}

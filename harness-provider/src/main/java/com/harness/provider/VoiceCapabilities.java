package com.harness.provider;

import java.util.List;

/**
 * Voice features exposed by the active provider.
 */
public record VoiceCapabilities(
        boolean asrAvailable,
        boolean ttsAvailable,
        boolean ttsStreamingAvailable,
        List<String> acceptedInputMimeTypes,
        List<String> outputFormats
) {
    public VoiceCapabilities {
        acceptedInputMimeTypes = acceptedInputMimeTypes != null
                ? List.copyOf(acceptedInputMimeTypes)
                : List.of();
        outputFormats = outputFormats != null ? List.copyOf(outputFormats) : List.of();
    }

    public static VoiceCapabilities unavailable() {
        return new VoiceCapabilities(false, false, false, List.of(), List.of());
    }
}

package com.harness.server;

import com.harness.ai.model.VoiceModelProvider;
import io.javalin.http.Context;

/**
 * GET /api/audio/capabilities
 */
public final class AudioCapabilityHandler {

    private final VoiceModelProvider voiceModelProvider;

    public AudioCapabilityHandler(VoiceModelProvider voiceModelProvider) {
        this.voiceModelProvider = java.util.Objects.requireNonNull(
                voiceModelProvider, "voiceModelProvider");
    }

    public void handle(Context context) {
        context.json(voiceModelProvider.capabilities());
    }
}

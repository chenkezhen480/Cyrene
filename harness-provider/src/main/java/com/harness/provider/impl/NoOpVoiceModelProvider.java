package com.harness.provider.impl;

import com.harness.provider.VoiceModelProvider;
import com.harness.provider.VoiceCapabilities;
import com.harness.core.modelconfig.ModelConfigKey;
import java.io.InputStream;

public class NoOpVoiceModelProvider implements VoiceModelProvider {
    @Override public String transcribe(InputStream audio, String mimeType) {
        throw unavailable();
    }
    @Override public byte[] synthesize(String text, String voice) {
        throw unavailable();
    }
    @Override public VoiceCapabilities capabilities() { return VoiceCapabilities.unavailable(); }
    @Override public String providerName() { return "none"; }
    private static UnsupportedOperationException unavailable() {
        return new UnsupportedOperationException("Voice model not configured. Set "
                + ModelConfigKey.VOICE_PROVIDER + " in model.conf.");
    }
}

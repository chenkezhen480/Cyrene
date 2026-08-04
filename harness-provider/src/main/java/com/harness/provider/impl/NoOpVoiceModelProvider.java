package com.harness.provider.impl;

import com.harness.provider.VoiceModelProvider;
import com.harness.provider.VoiceCapabilities;
import com.harness.core.env.EnvKey;
import java.io.InputStream;

public class NoOpVoiceModelProvider implements VoiceModelProvider {
    @Override public String transcribe(InputStream audio, String mimeType) {
        throw new UnsupportedOperationException("Voice model not configured. Set " + EnvKey.MODEL_VOICE_PROVIDER + ".");
    }
    @Override public byte[] synthesize(String text, String voice) {
        throw new UnsupportedOperationException("Voice model not configured. Set " + EnvKey.MODEL_VOICE_PROVIDER + ".");
    }
    @Override public VoiceCapabilities capabilities() { return VoiceCapabilities.unavailable(); }
    @Override public String providerName() { return "none"; }
}

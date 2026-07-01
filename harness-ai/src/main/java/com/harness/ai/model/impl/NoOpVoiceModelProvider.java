package com.harness.ai.model.impl;

import com.harness.ai.model.VoiceModelProvider;
import com.harness.env.EnvKey;
import java.io.InputStream;

public class NoOpVoiceModelProvider implements VoiceModelProvider {
    @Override public String transcribe(InputStream audio, String mimeType) {
        throw new UnsupportedOperationException("Voice model not configured. Set " + EnvKey.MODEL_VOICE_PROVIDER + ".");
    }
    @Override public byte[] synthesize(String text, String voice) {
        throw new UnsupportedOperationException("Voice model not configured. Set " + EnvKey.MODEL_VOICE_PROVIDER + ".");
    }
    @Override public String providerName() { return "none"; }
}

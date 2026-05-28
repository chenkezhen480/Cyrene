package com.harness.ai.model;

import java.io.InputStream;

/**
 * 3. Voice Model Provider
 * Handles: ASR (speech-to-text), TTS (text-to-speech).
 * Typically wraps external APIs (OpenAI Whisper, ElevenLabs, Azure Speech, etc.)
 */
public interface VoiceModelProvider {

    /**
     * Speech-to-text: transcribe audio to text.
     *
     * @param audio    audio input stream (wav, mp3, ogg, etc.)
     * @param mimeType audio MIME type
     * @return transcribed text
     */
    String transcribe(InputStream audio, String mimeType);

    /**
     * Text-to-speech: synthesize text to audio.
     *
     * @param text   text to speak
     * @param voice  voice identifier (provider-specific)
     * @return audio bytes (format depends on provider)
     */
    byte[] synthesize(String text, String voice);

    /**
     * Check if ASR is available.
     */
    default boolean isTranscribeAvailable() { return false; }

    /**
     * Check if TTS is available.
     */
    default boolean isSynthesizeAvailable() { return false; }

    String providerName();
}

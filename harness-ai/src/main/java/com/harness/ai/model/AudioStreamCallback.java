package com.harness.ai.model;

/**
 * Callback used by a voice provider while it reads a remote TTS response.
 */
public interface AudioStreamCallback {

    void onStart(long sequence, String mimeType);

    void onChunk(AudioChunk chunk);

    void onComplete(long sequence);

    void onError(long sequence, Throwable error);
}

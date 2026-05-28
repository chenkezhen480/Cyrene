package com.harness.ai.model;

/**
 * 6. Realtime Model Provider (Reserved)
 * Handles: real-time multimodal streaming (WebSocket-based).
 * For future use with OpenAI Realtime API, Gemini Live, etc.
 */
public interface RealtimeModelProvider {

    /**
     * Start a real-time session.
     *
     * @param handler callback handler for streaming events
     * @return session ID
     */
    String startSession(RealtimeEventHandler handler);

    /**
     * Send audio/text in real-time.
     */
    void send(String sessionId, byte[] data);

    /**
     * End a real-time session.
     */
    void endSession(String sessionId);

    /**
     * Check if this provider is available.
     */
    default boolean isAvailable() { return false; }

    String providerName();

    /**
     * Callback handler for real-time events.
     */
    interface RealtimeEventHandler {
        void onText(String text);
        void onAudio(byte[] audioData);
        void onToolCall(String toolName, String arguments);
        void onError(String error);
        void onEnd();
    }
}
